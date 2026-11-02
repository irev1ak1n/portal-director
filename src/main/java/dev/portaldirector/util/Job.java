package dev.portaldirector.util;

/** A long running task that is spread over many ticks. */
public interface Job {

    String describe();

    void cancel();
}
