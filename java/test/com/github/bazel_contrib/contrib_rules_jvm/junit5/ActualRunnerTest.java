package com.github.bazel_contrib.contrib_rules_jvm.junit5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ActualRunnerTest {

  @Test
  public void doesNotFailForUncaughtExceptionByDefault() {
    assertFalse(
        ActualRunner.shouldFailForUncaughtException(
            new RuntimeException("boom"), /* failOnUncaughtEnabled= */ false));
  }

  @Test
  public void failsForUncaughtExceptionWhenOptedIn() {
    assertTrue(
        ActualRunner.shouldFailForUncaughtException(
            new RuntimeException("boom"), /* failOnUncaughtEnabled= */ true));
  }

  @Test
  public void doesNotFailWhenOptedInButNothingEscapedUncaught() {
    assertFalse(
        ActualRunner.shouldFailForUncaughtException(
            /* uncaughtOnOtherThread= */ null, /* failOnUncaughtEnabled= */ true));
  }
}
