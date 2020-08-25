package org.opendatakit.briefcase.reused.job;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class JobsRunnerTest {
  // We can't declare these variables locally because the compiler
  // complains about them not being effectively final. Declaring
  // them as fields somehow works around it
  private JobsRunner runner;
  private AtomicInteger externalOutput;

  @Before
  public void setUp() {
    runner = null;
    externalOutput = new AtomicInteger(0);
  }

  @After
  public void tearDown() {
    if (runner != null)
      runner.cancel();
  }

  @Test
  public void can_launch_a_job_asynchronously_and_cancel_it() {
    int expectedOutput = 1;
    runner = JobsRunner.launchAsync(Job
        .supply(returnWhenCancelled(expectedOutput))
        .thenAccept((rs, result) -> externalOutput.accumulateAndGet(result, Integer::sum)));
    // Give a chance to the background thread to launch the job and give us the runner
    sleep(100);
    runner.cancel();
    // Give a chance to the success callback to update our test state
    sleep(100);
    assertThat(externalOutput.get(), is(expectedOutput));
  }

  @Test
  @Ignore
  public void can_launch_jobs_asynchronously_and_cancel_them() {
    runner = JobsRunner.launchAsync(IntStream.range(0, 100).mapToObj(n -> Job
        .supply(returnWhenCancelled(n))
        .thenAccept((rs, result) -> externalOutput.accumulateAndGet(result, Integer::sum))));
    // Give a chance to the background thread to launch the job and give us the runner
    sleep(100);
    runner.cancel();
    // Give a chance to the success callback to update our test state
    sleep(100);
    assertThat(externalOutput.get(), greaterThan(0));
  }

  @Test
  public void launched_async_jobs_will_eventually_end() {
    // Ensure that we will launch more Jobs than the thread pool's capacity
    runner = JobsRunner.launchAsync(IntStream.range(0, 1000).mapToObj(n -> Job
        .supply(__ -> 1)
        .thenAccept((rs, result) -> externalOutput.accumulateAndGet(result, Integer::sum))));
    // Give a chance to the jobs to complete
    sleep(100);
    assertThat(externalOutput.get(), is(1000));
  }

  @Test
  public void can_launch_a_job_synchronously() {
    assertThat(JobsRunner.launchSync(Job.supply(__ -> 1)), is(1));
  }

  @Test
  public void can_launch_jobs_synchronously() {
    List<Integer> result = JobsRunner.launchSync(IntStream.range(0, 1000).mapToObj(n -> Job.supply(__ -> 1)));
    assertThat(result, hasSize(1000));
    assertThat(result.stream().mapToInt(i -> i).sum(), is(1000));
  }

  private <T> Function<RunnerStatus, T> returnWhenCancelled(T t) {
    return runnerStatus -> {
      while (runnerStatus.isStillRunning()) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          // Do nothing
        }
      }
      return t;
    };
  }

  private void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

}
