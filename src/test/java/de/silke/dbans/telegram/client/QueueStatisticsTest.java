package de.silke.dbans.telegram.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueStatisticsTest {

    @Test
    void acceptsValidValues() {
        QueueStatistics stats = new QueueStatistics(10, 3, 2);

        assertThat(stats.capacity()).isEqualTo(10);
        assertThat(stats.depth()).isEqualTo(3);
        assertThat(stats.dropped()).isEqualTo(2);
    }

    @Test
    void rejectsNegativeCapacity() {
        assertThatThrownBy(() -> new QueueStatistics(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeDepth() {
        assertThatThrownBy(() -> new QueueStatistics(10, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeDropped() {
        assertThatThrownBy(() -> new QueueStatistics(10, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDepthExceedingCapacity() {
        assertThatThrownBy(() -> new QueueStatistics(5, 6, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsDepthEqualToCapacity() {
        QueueStatistics stats = new QueueStatistics(5, 5, 0);

        assertThat(stats.depth()).isEqualTo(stats.capacity());
    }

    @Test
    void allowsZeroCapacityWithZeroDepth() {
        QueueStatistics stats = new QueueStatistics(0, 0, 0);

        assertThat(stats.capacity()).isZero();
        assertThat(stats.depth()).isZero();
    }
}