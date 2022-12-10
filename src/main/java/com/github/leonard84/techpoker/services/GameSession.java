package com.github.leonard84.techpoker.services;

import static java.util.function.Function.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.github.leonard84.techpoker.data.Choice;
import com.github.leonard84.techpoker.data.ChoiceResult;
import com.github.leonard84.techpoker.data.GameId;
import com.github.leonard84.techpoker.data.GameStats;
import com.github.leonard84.techpoker.data.MedianIntConsumer;
import com.github.leonard84.techpoker.data.PlayerId;
import com.github.leonard84.techpoker.data.PlayerVote;
import com.github.leonard84.techpoker.data.Result;
import com.github.leonard84.techpoker.exceptions.DuplicatedPlayerException;
import jakarta.inject.Inject;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.util.Assert;

public class GameSession {

    @Inject
    private SimpMessagingTemplate simpMessagingTemplate;

    private final GameId id;

    private final Instant creationTime = Instant.now();

    private Instant lastUpdate = Instant.now();

    private Map<PlayerId, Choice> votes = new ConcurrentHashMap<>();

    private Map<PlayerId, String> players = new ConcurrentHashMap<>();

    private AtomicBoolean resetRequested = new AtomicBoolean();

    public GameSession() {
        this.id = new GameId();
    }

    public GameSession(GameId id) {
        Assert.notNull(id, "id may not be null");
        this.id = id;
    }

    public PlayerId join(String playerName) {
        Assert.notNull(playerName, "playerName may not be null");
        if (players.containsValue(playerName)) {
            throw new DuplicatedPlayerException();
        }
        updated();
        PlayerId playerId = new PlayerId();
        players.put(playerId, playerName);
        sendStats();
        return playerId;
    }

    public void vote(PlayerId playerId, Choice vote) {
        Assert.notNull(playerId, "playerId may not be null");
        Assert.notNull(vote, "vote may not be null");
        updated();
        votes.put(playerId, vote);
        sendStats();
    }

    public void kickPlayer(PlayerId id) {
        players.remove(id);
        votes.remove(id);
        updated();
        sendStats();
    }

    public void sendStats() {
        simpMessagingTemplate.convertAndSend("/topic/session/" + id + "/stats", getStats());
    }

    public Result tally() {
        updated();

        Map<Choice, Integer> grouped = votes.values().stream().collect(
                Collectors.groupingBy(identity(), Collectors.summingInt(i -> 1)));

        List<ChoiceResult> results = Arrays.stream(Choice.values())
                .map(choice -> new ChoiceResult(choice,
                        grouped.getOrDefault(choice, 0),
                        votes.entrySet().stream()
                                .filter(e -> e.getValue() == choice)
                                .map(e -> players.get(e.getKey()))
                                .sorted()
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());

        MedianIntConsumer medianIntConsumer = new MedianIntConsumer();
        IntSummaryStatistics statistics = resultStream().peek(medianIntConsumer).summaryStatistics();

        Result result = new Result(
                statistics.getAverage(),
                statistics.getMin(),
                statistics.getMax(),
                medianIntConsumer.getMedian(),
                results);
        simpMessagingTemplate.convertAndSend("/topic/session/" + id + "/result", result);
        return result;
    }

    public GameStats getStats() {
        Collection<PlayerVote> values = players.entrySet().stream()
                .map(player -> new PlayerVote(player.getKey(), player.getValue(), votes.containsKey(player.getKey())))
                .collect(Collectors.toList());
        return new GameStats(values, votes.size(), resetRequested.get());
    }

    public void requestReset() {
        if (resetRequested.compareAndSet(false, true)) {
            sendStats();
        }
    }

    public void reset() {
        resetRequested.set(false);
        updated();
        votes.clear();
        simpMessagingTemplate.convertAndSend("/topic/session/" + id + "/reset", "reset");
    }

    public Choice getVote(PlayerId playerId) {
        Assert.notNull(playerId, "playerId may not be null");
        Assert.isTrue(players.containsKey(playerId), "player is unknown");
        return votes.get(playerId);
    }

    public GameId getId() {
        return id;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public Instant getLastUpdate() {
        return lastUpdate;
    }

    private IntStream resultStream() {
        return votes.values().stream().filter(Choice::hasValue).mapToInt(Choice::getValue);
    }

    private void updated() {
        lastUpdate = Instant.now();
    }

    public String getName(PlayerId playerId) {
        return players.get(playerId);
    }
}
