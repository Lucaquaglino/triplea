package games.strategy.engine.lobby.client.login;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Level;

import com.google.common.annotations.VisibleForTesting;

import games.strategy.engine.ClientContext;
import games.strategy.engine.framework.map.download.DownloadUtils;
import games.strategy.triplea.UrlConstants;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.settings.GameSetting;
import games.strategy.util.Version;
import lombok.extern.java.Log;

/**
 * Fetches the lobby server properties from the remote Source of Truth.
 */
@Log
public final class LobbyServerPropertiesFetcher {
  private final LobbyLocationFileDownloader fileDownloader;

  /**
   * Default constructor with default (prod) dependencies.
   * This allows us to fetch a remote file and parse it for lobby properties.
   * Those properties then tell the game client how/where to connect to the lobby.
   */
  public LobbyServerPropertiesFetcher() {
    this(LobbyLocationFileDownloader.defaultDownloader);
  }

  @VisibleForTesting
  LobbyServerPropertiesFetcher(final LobbyLocationFileDownloader fileDownloader) {
    this.fileDownloader = fileDownloader;
  }

  /**
   * Fetches LobbyServerProperties based on values read from configuration.
   * LobbyServerProperties are based on a properties file hosted on github which can be updated live.
   * This properties file tells the game client how to connect to the lobby and provides a welcome message.
   * In case Github is not available, we also have a backup hardcoded lobby host address in the client
   * configuration that we would pass back in case github is not available.
   *
   * <p>
   * The lobby server properties may be overridden by setting values for {@link ClientSetting#testLobbyHost} and
   * {@link ClientSetting#testLobbyPort} simultaneously. Setting only one or the other will cause them to be ignored.
   * </p>
   *
   * @return LobbyServerProperties as fetched and parsed from github hosted remote URL.
   *         Otherwise backup values from client config.
   */
  public LobbyServerProperties fetchLobbyServerProperties() {
    return getTestOverrideProperties().orElseGet(this::getRemoteProperties);
  }

  private static Optional<LobbyServerProperties> getTestOverrideProperties() {
    return getTestOverrideProperties(ClientSetting.testLobbyHost, ClientSetting.testLobbyPort);
  }

  @VisibleForTesting
  static Optional<LobbyServerProperties> getTestOverrideProperties(
      final GameSetting<String> testLobbyHostSetting,
      final GameSetting<Integer> testLobbyPortSetting) {
    if (testLobbyHostSetting.isSet() && testLobbyPortSetting.isSet()) {
      return Optional.of(new LobbyServerProperties(
          testLobbyHostSetting.getValueOrThrow(),
          testLobbyPortSetting.getValueOrThrow()));
    }

    return Optional.empty();
  }

  private LobbyServerProperties getRemoteProperties() {
    final String lobbyPropsUrl = UrlConstants.LOBBY_PROPS.toString();

    final Version currentVersion = ClientContext.engineVersion();

    try {
      final LobbyServerProperties downloadedProps = downloadAndParseRemoteFile(lobbyPropsUrl, currentVersion,
          LobbyPropertyFileParser::parse);
      ClientSetting.lobbyLastUsedHost.setValue(downloadedProps.host);
      ClientSetting.lobbyLastUsedPort.setValue(downloadedProps.port);
      ClientSetting.flush();
      return downloadedProps;
    } catch (final IOException e) {
      if (!ClientSetting.lobbyLastUsedHost.isSet() || !ClientSetting.lobbyLastUsedPort.isSet()) {
        log.log(Level.SEVERE,
            String.format("Failed to download lobby server property file from %s; "
                + "Please verify your internet connection and try again.",
                lobbyPropsUrl),
            e);
        throw new RuntimeException(e);
      }

      // graceful recovery case, use the last lobby address we knew about
      log.log(Level.SEVERE, "Encountered an error while downloading lobby property file: " + lobbyPropsUrl
          + ", will attempt to connect to the lobby at its last known address. If this problem keeps happening, "
          + "you may be seeing network troubles, or the lobby may not be available.", e);
      return new LobbyServerProperties(
          ClientSetting.lobbyLastUsedHost.getValueOrThrow(),
          ClientSetting.lobbyLastUsedPort.getValueOrThrow());
    }
  }

  /**
   * Downloads the lobby properties file from the specified URL and returns the parsed properties.
   *
   * @param lobbyPropFileUrl The target URL to scrape for a lobby properties file.
   * @param currentVersion Our current engine version. The properties file can contain
   *        multiple listings for different versions.
   * @return Parsed LobbyServerProperties object from the data we found at the remote url.
   * @throws IOException Thrown if there is a failure doing the remote network fetching
   *         or IO problem once we downloaded the remote file to a temp file and are then reading it.
   */
  @VisibleForTesting
  LobbyServerProperties downloadAndParseRemoteFile(
      final String lobbyPropFileUrl,
      final Version currentVersion,
      final BiFunction<File, Version, LobbyServerProperties> propertyParser)
      throws IOException {
    final DownloadUtils.FileDownloadResult fileDownloadResult = fileDownloader.download(lobbyPropFileUrl);

    if (!fileDownloadResult.wasSuccess) {
      throw new IOException("Failed to download: " + lobbyPropFileUrl);
    }

    final LobbyServerProperties properties =
        propertyParser.apply(fileDownloadResult.downloadedFile, currentVersion);

    // delete file after it has been used. If there there was an IOException, the 'deleteOnExit' should
    // kick in and delete the file. (hence there is no try/catch/finally block here)
    fileDownloadResult.downloadedFile.delete();
    return properties;
  }
}