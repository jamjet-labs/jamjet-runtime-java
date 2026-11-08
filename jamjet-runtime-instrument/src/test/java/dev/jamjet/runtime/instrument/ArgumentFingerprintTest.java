package dev.jamjet.runtime.instrument;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgumentFingerprintTest {

    record Customer(String name, int tier, List<String> tags) {}

    public static class Order {
        private final String sku;
        private final int qty;

        public Order(String sku, int qty) {
            this.sku = sku;
            this.qty = qty;
        }

        public String getSku() {
            return sku;
        }

        public int getQty() {
            return qty;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Order other && other.sku.equals(sku) && other.qty == qty;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(sku, qty);
        }
    }

    /** A value object that can point at itself. */
    public static class Node {
        public final String label = "n";
        public Node next;

        @Override
        public boolean equals(Object o) {
            return o instanceof Node other && other.label.equals(label);
        }

        @Override
        public int hashCode() {
            return label.hashCode();
        }
    }

    private static final Method M = method();

    private static Method method() {
        try {
            return ArgumentFingerprintTest.class.getDeclaredMethod("method");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static String fp(Object... args) {
        return ArgumentFingerprint.of(M, args);
    }

    @Test
    void equalArgumentsGiveEqualFingerprints() {
        assertThat(fp("alice", 5L)).isEqualTo(fp("alice", 5L));
        assertThat(fp(new Customer("a", 1, List.of("x")))).isEqualTo(fp(new Customer("a", 1, List.of("x"))));
        assertThat(fp(new Order("sku-1", 2))).isEqualTo(fp(new Order("sku-1", 2)));
        UUID id = UUID.randomUUID();
        assertThat(fp(id, Instant.EPOCH)).isEqualTo(fp(UUID.fromString(id.toString()), Instant.ofEpochSecond(0)));
    }

    @Test
    void differentArgumentsGiveDifferentFingerprints() {
        assertThat(fp("alice")).isNotEqualTo(fp("bob"));
        assertThat(fp("alice", 5L)).isNotEqualTo(fp("alice", 6L));
        // type is part of the value: 5 and 5L are different arguments
        assertThat(fp(5)).isNotEqualTo(fp(5L));
        assertThat(fp(new BigDecimal("1.0"))).isNotEqualTo(fp(new BigDecimal("1.00")));
        assertThat(fp((Object) null)).isNotEqualTo(fp("N"));
        // argument boundaries count: ("ab", "c") is not ("a", "bc")
        assertThat(fp("ab", "c")).isNotEqualTo(fp("a", "bc"));
        assertThat(fp(new Customer("a", 1, List.of()))).isNotEqualTo(fp(new Customer("a", 2, List.of())));
        assertThat(fp(new Order("sku-1", 2))).isNotEqualTo(fp(new Order("sku-1", 3)));
        assertThat(fp(Optional.of("a"))).isNotEqualTo(fp(Optional.empty()));
        assertThat(fp((Object) new int[]{1, 2})).isNotEqualTo(fp((Object) new int[]{2, 1}));
        assertThat(fp(List.of("a", "b"))).isNotEqualTo(fp(List.of("b", "a")));
    }

    @Test
    void setsAndMapsIgnoreIterationOrder() {
        Set<String> ab = new LinkedHashSet<>(List.of("a", "b"));
        Set<String> ba = new LinkedHashSet<>(List.of("b", "a"));
        assertThat(fp(ab)).isEqualTo(fp(ba));

        Map<String, Integer> one = new LinkedHashMap<>();
        one.put("x", 1);
        one.put("y", 2);
        Map<String, Integer> two = new HashMap<>();
        two.put("y", 2);
        two.put("x", 1);
        assertThat(fp(one)).isEqualTo(fp(two));
        two.put("x", 3);
        assertThat(fp(one)).isNotEqualTo(fp(two));
    }

    @Test
    void anObjectWithIdentitySemanticsIsRefused() {
        Object opaque = new Object();
        assertThatThrownBy(() -> fp(opaque))
                .isInstanceOf(CheckpointKeyException.class)
                .hasMessageContaining("argument 0")
                .hasMessageContaining("java.lang.Object");
    }

    @Test
    void aCycleIsRefused() {
        List<Object> self = new ArrayList<>();
        self.add(self);
        assertThatThrownBy(() -> fp(self)).isInstanceOf(CheckpointKeyException.class);

        Node node = new Node();
        node.next = node;
        assertThatThrownBy(() -> fp(node)).isInstanceOf(CheckpointKeyException.class);
    }
}
