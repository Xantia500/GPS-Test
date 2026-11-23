package com.example.gpstest;

public final class CallbackGenerationGateTest {
    public static void main(String[] args) {
        CallbackGenerationGate gate = new CallbackGenerationGate();
        long first = gate.snapshot();
        check(gate.isCurrent(first), "initial generation is current");
        check(gate.invalidate() == first + 1L, "invalidation advances generation");
        check(!gate.isCurrent(first), "previous callbacks become stale");

        long second = gate.snapshot();
        check(gate.isCurrent(second), "new callbacks capture current generation");
        gate.invalidate();
        check(!gate.isCurrent(second), "second generation is invalidated");
        long third = gate.snapshot();
        check(gate.isCurrent(third), "gate accepts a later generation");
        check(!gate.isCurrent(first), "old token never becomes current again");
        check(!gate.isCurrent(second), "intermediate token never becomes current again");

        CallbackGenerationGate independent = new CallbackGenerationGate();
        long independentToken = independent.snapshot();
        gate.invalidate();
        check(independent.isCurrent(independentToken), "separate callback channels stay independent");
        System.out.println("RESULTAT : 9 reussis, 0 echoues");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
