package com.github.bazel_contrib.contrib_rules_jvm.junit5;

import static java.nio.file.StandardOpenOption.DELETE_ON_CLOSE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.platform.launcher.EngineFilter.excludeEngines;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.engine.Constants;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherConstants;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

public class ActualRunner implements RunsTest {

  @Override
  public boolean run(String testClassName) {
    String out = System.getenv("XML_OUTPUT_FILE");
    Path xmlOut;
    try {
      xmlOut = out != null ? Paths.get(out) : Files.createTempFile("test", ".xml");
      Files.createDirectories(xmlOut.getParent());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    // Catches exceptions that escape uncaught on threads the test spawns but never joins/awaits
    // (e.g. a raw `new Thread(...)` whose crash would otherwise vanish, since the test method
    // itself returns normally and the engine never learns anything went wrong). This only helps
    // for threads with no uncaught exception handler of their own: exceptions swallowed inside an
    // ExecutorService/Future/CompletableFuture/coroutine that is never awaited, or a crash that
    // happens strictly after this method has already decided pass/fail, are both invisible to
    // this (or any other) listener-based mechanism.
    AtomicReference<Throwable> uncaughtOnOtherThread = new AtomicReference<>();
    Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> {
          uncaughtOnOtherThread.compareAndSet(null, throwable);
          if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable);
          }
        });

    try {
      return runWithOutputListener(testClassName, xmlOut, uncaughtOnOtherThread);
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }
  }

  private boolean runWithOutputListener(
      String testClassName, Path xmlOut, AtomicReference<Throwable> uncaughtOnOtherThread) {
    try (BazelJUnitOutputListener bazelJUnitXml = new BazelJUnitOutputListener(xmlOut)) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    bazelJUnitXml.closeForInterrupt();
                  }));

      CommandLineSummary summary = new CommandLineSummary();
      FailFastExtension failFastExtension = new FailFastExtension();

      LauncherConfig config =
          LauncherConfig.builder()
              .addTestExecutionListeners(bazelJUnitXml, summary, failFastExtension)
              .addPostDiscoveryFilters(TestSharding.makeShardFilter())
              .build();

      final Class<?> testClass;
      try {
        testClass = Class.forName(testClassName, false, getClass().getClassLoader());
      } catch (ClassNotFoundException e) {
        throw new RuntimeException("Failed to find testClass", e);
      }

      // We only allow for one level of nesting at the moment
      boolean enclosed = isRunWithEnclosed(testClass);
      List<DiscoverySelector> classSelectors =
          enclosed
              ? new ArrayList<>()
              : Arrays.stream(testClass.getDeclaredClasses())
                  .filter(clazz -> Modifier.isStatic(clazz.getModifiers()))
                  .map(DiscoverySelectors::selectClass)
                  .collect(Collectors.toList());

      classSelectors.add(DiscoverySelectors.selectClass(testClassName));

      LauncherDiscoveryRequestBuilder request =
          LauncherDiscoveryRequestBuilder.request()
              .selectors(classSelectors)
              .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "true")
              .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "true")
              .configurationParameter(
                  Constants.EXTENSIONS_AUTODETECTION_ENABLED_PROPERTY_NAME, "true");

      String filter = System.getenv("TESTBRIDGE_TEST_ONLY");
      request.filters(new PatternFilter(filter));

      String includeTags = System.getProperty("JUNIT5_INCLUDE_TAGS");
      if (includeTags != null && !includeTags.isEmpty()) {
        request.filters(TagFilter.includeTags(includeTags.split(",")));
      }

      String excludeTags = System.getProperty("JUNIT5_EXCLUDE_TAGS");
      if (excludeTags != null && !excludeTags.isEmpty()) {
        request.filters(TagFilter.excludeTags(excludeTags.split(",")));
      }

      List<String> includeEngines =
          System.getProperty("JUNIT5_INCLUDE_ENGINES") == null
              ? null
              : Arrays.asList(System.getProperty("JUNIT5_INCLUDE_ENGINES").split(","));
      List<String> excludeEngines =
          System.getProperty("JUNIT5_EXCLUDE_ENGINES") == null
              ? null
              : Arrays.asList(System.getProperty("JUNIT5_EXCLUDE_ENGINES").split(","));
      if (includeEngines != null) {
        request.filters(includeEngines(includeEngines));
      }
      if (excludeEngines != null) {
        request.filters(excludeEngines(excludeEngines));
      }

      File exitFile = getExitFile();

      Launcher launcher = LauncherFactory.create(config);
      launcher.execute(request.build());

      deleteExitFile(exitFile);

      try (PrintWriter writer = new PrintWriter(System.out)) {
        summary.writeTo(writer);
      }

      Throwable uncaught = uncaughtOnOtherThread.get();
      if (uncaught != null) {
        System.err.printf(
            "WARNING: %s: an exception escaped uncaught on another thread (%s). This was not"
                + " reflected in any individual test result, since the test engine was never"
                + " told about it:%n",
            testClassName, uncaught.getClass().getName());
        uncaught.printStackTrace(System.err);
        boolean failOnUncaughtEnabled =
            Boolean.parseBoolean(System.getenv("JUNIT5_FAIL_ON_UNCAUGHT_EXCEPTIONS"));
        if (shouldFailForUncaughtException(uncaught, failOnUncaughtEnabled)) {
          return false;
        }
      }

      boolean failIfNoTestsEnabled = Boolean.parseBoolean(System.getenv("JUNIT5_FAIL_IF_NO_TESTS"));
      if (shouldFailForNoTests(summary, failIfNoTestsEnabled)) {
        System.err.printf(
            "ERROR: %s matched zero tests. This usually means a wrong test_class, a typo in"
                + " TESTBRIDGE_TEST_ONLY, or a tag filter that excludes everything.%n",
            testClassName);
        return false;
      }

      return summary.getFailureCount() == 0;
    }
  }

  /**
   * Mirrors the polarity of JUnit's own {@code ConsoleLauncher --fail-if-no-tests} option:
   * disabled unless explicitly requested, so that enabling this check is an opt-in, non-breaking
   * change for existing callers. A real failure (e.g. a crash during test class construction)
   * always takes precedence over the generic "matched zero tests" message.
   */
  static boolean shouldFailForNoTests(CommandLineSummary summary, boolean failIfNoTestsEnabled) {
    return summary.getFailureCount() == 0
        && summary.getTestCount() == 0
        && failIfNoTestsEnabled;
  }

  /**
   * Opt-in (disabled by default, same non-breaking rationale as {@link #shouldFailForNoTests}):
   * whether an exception that escaped uncaught on a thread the test never joined/awaited should
   * fail the test, even though the test engine itself has no idea anything went wrong.
   */
  static boolean shouldFailForUncaughtException(
      Throwable uncaughtOnOtherThread, boolean failOnUncaughtEnabled) {
    return uncaughtOnOtherThread != null && failOnUncaughtEnabled;
  }

  /**
   * Checks if the test class is annotation with `@RunWith(Enclosed.class)`. We deliberately avoid
   * using types here to avoid polluting the classpath with junit4 deps.
   */
  private boolean isRunWithEnclosed(Class<?> clazz) {
    for (Annotation annotation : clazz.getAnnotations()) {
      Class<? extends Annotation> type = annotation.annotationType();
      if (type.getName().equals("org.junit.runner.RunWith")) {
        try {
          Class<?> runner = (Class<?>) type.getMethod("value").invoke(annotation, (Object[]) null);
          if (runner.getName().equals("org.junit.experimental.runners.Enclosed")) {
            return true;
          }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
          return false;
        }
      }
    }
    return false;
  }

  private File getExitFile() {
    String exitFileName = System.getenv("TEST_PREMATURE_EXIT_FILE");
    if (exitFileName == null) {
      return null;
    }

    File exitFile = new File(exitFileName);
    try {
      Files.write(exitFile.toPath(), "".getBytes(), WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
    } catch (IOException e) {
      return null;
    }

    return exitFile;
  }

  private void deleteExitFile(File exitFile) {
    if (exitFile != null) {
      try {
        exitFile.delete();
      } catch (Throwable t) {
        // Ignore.
      }
    }
  }
}
