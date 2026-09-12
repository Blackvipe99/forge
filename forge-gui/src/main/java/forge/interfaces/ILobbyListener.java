package forge.interfaces;

import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;

public interface ILobbyListener {
    void message(String source, String message, ChatMessage.MessageType type);
    void update(GameLobbyData state, int slot);
    void close();
    /**
     * The connection closed and the server said why (e.g. it refused our login).
     * Defaults to the plain {@link #close()} for listeners that do not care.
     */
    default void close(final String reason) {
        close();
    }
    ClientGameLobby getLobby();
}
