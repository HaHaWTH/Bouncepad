package com.cleanroommc.bouncepad.test.util;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Benchmarker {

    public static void run(Class<?> clazz) {
        new Benchmarker(clazz, t -> { }).run();
    }

    public static void run(Class<?> clazz, Consumer<OptionsBuilder> options) {
        new Benchmarker(clazz, options).run();
    }

    private final OptionsBuilder optionsBuilder;

    private Benchmarker(Class<?> clazz, Consumer<OptionsBuilder> options) {
        this.optionsBuilder = (OptionsBuilder) new OptionsBuilder()
                .include(clazz.getName() + ".*")
                .mode(Mode.AverageTime)
                .timeUnit(TimeUnit.MICROSECONDS)
                .warmupTime(TimeValue.seconds(1))
                .warmupIterations(5)
                .measurementTime(TimeValue.seconds(1))
                .measurementIterations(5)
                .threads(2)
                .forks(1)
                .shouldFailOnError(true)
                .shouldDoGC(true);
        options.accept(this.optionsBuilder);
    }

    public void run() {
        try {
            new Runner(this.optionsBuilder).run();
        } catch (RunnerException e) {
            throw new RuntimeException(e);
        }
    }

}
