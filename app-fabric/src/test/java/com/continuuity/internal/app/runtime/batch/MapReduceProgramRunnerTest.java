package com.continuuity.internal.app.runtime.batch;

import com.continuuity.api.common.Bytes;
import com.continuuity.api.data.dataset.KeyValueTable;
import com.continuuity.api.data.dataset.SimpleTimeseriesTable;
import com.continuuity.api.data.dataset.TimeseriesTable;
import com.continuuity.api.data.dataset.table.Get;
import com.continuuity.api.data.dataset.table.Table;
import com.continuuity.app.program.Program;
import com.continuuity.app.runtime.ProgramController;
import com.continuuity.app.runtime.ProgramRunner;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.data.DataFabric2Impl;
import com.continuuity.data.DataSetAccessor;
import com.continuuity.data.dataset.DataSetInstantiator;
import com.continuuity.data2.transaction.TransactionExecutor;
import com.continuuity.data2.transaction.TransactionExecutorFactory;
import com.continuuity.data2.transaction.TransactionFailureException;
import com.continuuity.data2.transaction.inmemory.InMemoryTransactionManager;
import com.continuuity.internal.app.deploy.pipeline.ApplicationWithPrograms;
import com.continuuity.internal.app.runtime.BasicArguments;
import com.continuuity.internal.app.runtime.ProgramRunnerFactory;
import com.continuuity.internal.app.runtime.SimpleProgramOptions;
import com.continuuity.test.internal.TestHelper;
import com.continuuity.weave.filesystem.LocationFactory;
import com.google.common.collect.Maps;
import com.google.inject.Injector;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class MapReduceProgramRunnerTest {
  private static Injector injector;
  private static TransactionExecutorFactory txExecutorFactory;

  private DataSetInstantiator dataSetInstantiator;
  private DataSetAccessor dataSetAccessor;

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();

  @BeforeClass
  public static void beforeClass() {
    // we are only gonna do long-running transactions here. Set the tx timeout to a ridiculously low value.
    // that will test that the long-running transactions actually bypass that timeout.
    CConfiguration conf = CConfiguration.create();
    conf.setInt(Constants.Transaction.Manager.CFG_TX_TIMEOUT, 1);
    conf.setInt(Constants.Transaction.Manager.CFG_TX_CLEANUP_INTERVAL, 2);
    injector = TestHelper.getInjector(conf);
    txExecutorFactory = injector.getInstance(TransactionExecutorFactory.class);
  }

  @Before
  public void before() {
    injector.getInstance(InMemoryTransactionManager.class).startAndWait();
    LocationFactory locationFactory = injector.getInstance(LocationFactory.class);
    dataSetAccessor = injector.getInstance(DataSetAccessor.class);
    dataSetInstantiator =
      new DataSetInstantiator(new DataFabric2Impl(locationFactory, dataSetAccessor),
                              getClass().getClassLoader());
  }

  @After
  public void after() throws Exception {
    cleanupData();
  }

  @Test
  public void testWordCount() throws Exception {
    final ApplicationWithPrograms app = TestHelper.deployApplicationWithManager(AppWithMapReduce.class);

    final String inputPath = createInput();
    final File outputDir = new File(tmpFolder.newFolder(), "output");

    dataSetInstantiator.setDataSets(new AppWithMapReduce().configure().getDataSets().values());
    final KeyValueTable jobConfigTable = dataSetInstantiator.getDataSet("jobConfig");

    // write config into dataset
    txExecutorFactory.createExecutor(dataSetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() {
          jobConfigTable.write(Bytes.toBytes("inputPath"), Bytes.toBytes(inputPath));
          jobConfigTable.write(Bytes.toBytes("outputPath"), Bytes.toBytes(outputDir.getPath()));
        }
      });

    runProgram(app, AppWithMapReduce.ClassicWordCount.class, false);

    File[] outputFiles = outputDir.listFiles();
    Assert.assertNotNull("no output files found", outputFiles);
    Assert.assertTrue("no output files found", outputFiles.length > 0);
    File outputFile = outputFiles[0];
    int lines = 0;
    BufferedReader reader = new BufferedReader(new FileReader(outputFile));
    try {
      while (true) {
        String line = reader.readLine();
        if (line == null) {
          break;
        }
        lines++;
      }
    } finally {
      reader.close();
    }
    // dummy check that output file is not empty
    Assert.assertTrue(lines > 0);
  }

  @Test
  public void testJobSuccess() throws Exception {
    testSuccess(false);
  }

  @Test
  public void testJobSuccessWithFrequentFlushing() throws Exception {
    // simplest test for periodic flushing
    // NOTE: we will change auto-flush to take into account size of buffered data, so no need to do/test a lot with
    //       current approach
    testSuccess(true);
  }

  private void testSuccess(boolean frequentFlushing) throws Exception {
    final ApplicationWithPrograms app = TestHelper.deployApplicationWithManager(AppWithMapReduce.class);
    dataSetInstantiator.setDataSets(new AppWithMapReduce().configure().getDataSets().values());

    // we need to do a "get" on all datasets we use so that they are in dataSetInstantiator.getTransactionAware()
    final TimeseriesTable table = (TimeseriesTable) dataSetInstantiator.getDataSet("timeSeries");
    final KeyValueTable beforeSubmitTable = dataSetInstantiator.getDataSet("beforeSubmit");
    final KeyValueTable onFinishTable = dataSetInstantiator.getDataSet("onFinish");
    final Table counters = dataSetInstantiator.getDataSet("counters");

    // 1) fill test data
    fillTestInputData(txExecutorFactory, dataSetInstantiator, table, false);

    // 2) run job
    final long start = System.currentTimeMillis();
    runProgram(app, AppWithMapReduce.AggregateTimeseriesByTag.class, frequentFlushing);
    final long stop = System.currentTimeMillis();

    // 3) verify results
    txExecutorFactory.createExecutor(dataSetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() {
          Map<String, Long> expected = Maps.newHashMap();
          // note: not all records add to the sum since filter by tag="tag1" and ts={1..3} is used
          expected.put("tag1", 18L);
          expected.put("tag2", 3L);
          expected.put("tag3", 18L);

          List<TimeseriesTable.Entry> agg = table.read(AggregateMetricsByTag.BY_TAGS, start, stop);
          Assert.assertEquals(expected.size(), agg.size());
          for (TimeseriesTable.Entry entry : agg) {
            String tag = Bytes.toString(entry.getTags()[0]);
            Assert.assertEquals((long) expected.get(tag), Bytes.toLong(entry.getValue()));
          }

          Assert.assertArrayEquals(Bytes.toBytes("beforeSubmit:done"),
                                   beforeSubmitTable.read(Bytes.toBytes("beforeSubmit")));
          Assert.assertArrayEquals(Bytes.toBytes("onFinish:done"),
                                   onFinishTable.read(Bytes.toBytes("onFinish")));

          Assert.assertTrue(counters.get(new Get("mapper")).getLong("records", 0) > 0);
          Assert.assertTrue(counters.get(new Get("reducer")).getLong("records", 0) > 0);
        }
      });
  }

  @Test
  public void testJobFailure() throws Exception {
    testFailure(false);
  }

  @Test
  public void testJobFailureWithFrequentFlushing() throws Exception {
    testFailure(true);
  }

  // TODO: this tests failure in Map tasks. We also need to test: failure in Reduce task, kill of a job by user.
  private void testFailure(boolean frequentFlushing) throws Exception {
    // We want to verify that when mapreduce job fails:
    // * things written in beforeSubmit() remains and visible to others
    // * things written in tasks not visible to others TODO AAA: do invalidate
    // * things written in onfinish() remains and visible to others

    // NOTE: the code of this test is similar to testTimeSeriesRecordsCount() test. We put some "bad data" intentionally
    //       here to be recognized by map tasks as a message to emulate failure

    final ApplicationWithPrograms app = TestHelper.deployApplicationWithManager(AppWithMapReduce.class);
    dataSetInstantiator.setDataSets(new AppWithMapReduce().configure().getDataSets().values());

    // we need to do a "get" on all datasets we use so that they are in dataSetInstantiator.getTransactionAware()
    final TimeseriesTable table = (TimeseriesTable) dataSetInstantiator.getDataSet("timeSeries");
    final KeyValueTable beforeSubmitTable = dataSetInstantiator.getDataSet("beforeSubmit");
    final KeyValueTable onFinishTable = dataSetInstantiator.getDataSet("onFinish");
    final Table counters = dataSetInstantiator.getDataSet("counters");

    // 1) fill test data
    fillTestInputData(txExecutorFactory, dataSetInstantiator, table, true);

    // 2) run job
    final long start = System.currentTimeMillis();
    runProgram(app, AppWithMapReduce.AggregateTimeseriesByTag.class, frequentFlushing);
    final long stop = System.currentTimeMillis();

    // 3) verify results
    txExecutorFactory.createExecutor(dataSetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() {
          // data should be rolled back todo: test that partially written is rolled back too
          Assert.assertTrue(table.read(AggregateMetricsByTag.BY_TAGS, start, stop).isEmpty());

          // but written beforeSubmit and onFinish is available to others
          Assert.assertArrayEquals(Bytes.toBytes("beforeSubmit:done"),
                                   beforeSubmitTable.read(Bytes.toBytes("beforeSubmit")));
          Assert.assertArrayEquals(Bytes.toBytes("onFinish:done"),
                                   onFinishTable.read(Bytes.toBytes("onFinish")));
          Assert.assertEquals(0, counters.get(new Get("mapper")).getLong("records", 0));
          Assert.assertEquals(0, counters.get(new Get("reducer")).getLong("records", 0));
        }
    });
  }

  private void cleanupData() throws Exception {
    // quite hacky way to drop all user datasets and cleanup all system datasets
    // todo: To be improved with DataSetService
    dataSetAccessor.dropAll(DataSetAccessor.Namespace.USER);
    dataSetAccessor.truncateAll(DataSetAccessor.Namespace.SYSTEM);
  }


  private void fillTestInputData(TransactionExecutorFactory txExecutorFactory,
                                 DataSetInstantiator dataSetInstantiator,
                                 final TimeseriesTable table,
                                 final boolean withBadData) throws TransactionFailureException {
    TransactionExecutor executor = txExecutorFactory.createExecutor(dataSetInstantiator.getTransactionAware());
    executor.execute(new TransactionExecutor.Subroutine() {
      @Override
      public void apply() {
        fillTestInputData(table, withBadData);
      }
    });
  }

  private void fillTestInputData(TimeseriesTable table, boolean withBadData) {
    byte[] metric1 = Bytes.toBytes("metric");
    byte[] metric2 = Bytes.toBytes("metric2");
    byte[] tag1 = Bytes.toBytes("tag1");
    byte[] tag2 = Bytes.toBytes("tag2");
    byte[] tag3 = Bytes.toBytes("tag3");
    // m1e1 = metric: 1, entity: 1
    table.write(new SimpleTimeseriesTable.Entry(metric1, Bytes.toBytes(3L), 1, tag3, tag2, tag1));
    table.write(new SimpleTimeseriesTable.Entry(metric1, Bytes.toBytes(10L), 2, tag2, tag3));
    // 55L will make job fail
    table.write(new SimpleTimeseriesTable.Entry(metric1, Bytes.toBytes(withBadData ? 55L : 15L), 3, tag1, tag3));
    table.write(new SimpleTimeseriesTable.Entry(metric1, Bytes.toBytes(23L), 4, tag2));


    table.write(new SimpleTimeseriesTable.Entry(metric2, Bytes.toBytes(4L), 3, tag1, tag3));
  }

  private void runProgram(ApplicationWithPrograms app, Class<?> programClass, boolean frequentFlushing)
    throws Exception {
    waitForCompletion(submit(app, programClass, frequentFlushing));
  }

  private void waitForCompletion(ProgramController controller) throws InterruptedException {
    while (controller.getState() == ProgramController.State.ALIVE) {
      TimeUnit.SECONDS.sleep(1);
    }
  }

  private ProgramController submit(ApplicationWithPrograms app, Class<?> programClass, boolean frequentFlushing)
    throws ClassNotFoundException {
    ProgramRunnerFactory runnerFactory = injector.getInstance(ProgramRunnerFactory.class);
    final Program program = getProgram(app, programClass);
    ProgramRunner runner = runnerFactory.create(ProgramRunnerFactory.Type.valueOf(program.getType().name()));

    HashMap<String, String> userArgs = Maps.newHashMap();
    userArgs.put("metric", "metric");
    userArgs.put("startTs", "1");
    userArgs.put("stopTs", "3");
    userArgs.put("tag", "tag1");
    if (frequentFlushing) {
      userArgs.put("frequentFlushing", "true");
    }
    return runner.run(program, new SimpleProgramOptions(program.getName(),
                                                        new BasicArguments(),
                                                        new BasicArguments(userArgs)));
  }

  private Program getProgram(ApplicationWithPrograms app, Class<?> programClass) throws ClassNotFoundException {
    for (Program p : app.getPrograms()) {
      if (programClass.getCanonicalName().equals(p.getMainClass().getCanonicalName())) {
        return p;
      }
    }
    return null;
  }

  private String createInput() throws IOException {
    File inputDir = tmpFolder.newFolder();

    File inputFile = new File(inputDir.getPath() + "/words.txt");
    inputFile.deleteOnExit();
    BufferedWriter writer = new BufferedWriter(new FileWriter(inputFile));
    try {
      writer.write("this text has");
      writer.newLine();
      writer.write("two words text inside");
    } finally {
      writer.close();
    }

    return inputDir.getPath();
  }

}
