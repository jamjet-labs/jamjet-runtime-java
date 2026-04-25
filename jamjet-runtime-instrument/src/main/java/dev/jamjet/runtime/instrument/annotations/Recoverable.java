package dev.jamjet.runtime.instrument.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as recoverable — the runtime may retry or compensate this method
 * on failure. Unlike {@link Checkpoint}, {@code @Recoverable} does not cache return
 * values; it signals retry intent to the JamJet scheduler.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Recoverable {
}
