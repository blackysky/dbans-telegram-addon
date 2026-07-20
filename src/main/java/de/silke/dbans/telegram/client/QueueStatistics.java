package de.silke.dbans.telegram.client;

public record QueueStatistics(int capacity, int depth, long dropped) {

    public QueueStatistics {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        if (dropped < 0) {
            throw new IllegalArgumentException("dropped must not be negative");
        }
    }

}