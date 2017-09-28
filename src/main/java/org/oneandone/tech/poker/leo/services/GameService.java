package org.oneandone.tech.poker.leo.services;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Provider;

import org.oneandone.tech.poker.leo.config.PokerProperties;
import org.oneandone.tech.poker.leo.data.GameId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GameService {
    private static final Logger LOG = LoggerFactory.getLogger(GameService.class);

    private Map<GameId, GameSession> games = new ConcurrentHashMap<>();

    public GameSession getById(GameId gameId) {
        return games.get(gameId);
    }

    @Inject
    private Provider<GameSession> gameSessionProvider;

    @Inject
    private PokerProperties pokerProperties;

    public GameId createNewGame() {
        LOG.info("New session created.");
        GameSession gameSession = gameSessionProvider.get();
        games.put(gameSession.getId(), gameSession);
        return gameSession.getId();
    }

    @Scheduled(fixedDelayString = "#{pokerProperties.cleanupInterval}")
    public void cleanupOldSessions() {
        LOG.info("Expiring old sessions.");
        int oldSize = games.size();
        Instant expiredTimestamp = Instant.now().minus(pokerProperties.getCleanupExpired(), ChronoUnit.MILLIS);
        games.entrySet().removeIf(sessionEntry -> sessionEntry.getValue().getLastUpdate().isBefore(expiredTimestamp));
        int newSize = games.size();
        LOG.info("Expired {} sessions. {} sessions are still active.", oldSize - newSize, newSize);
    }
}
