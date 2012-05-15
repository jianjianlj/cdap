/**
 * 
 */
package com.continuuity.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.hbase.util.Bytes;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.continuuity.fabric.engine.memory.MemoryEngine;
import com.continuuity.fabric.engine.memory.MemorySimpleExecutor;
import com.continuuity.fabric.operations.OperationExecutor;
import com.continuuity.fabric.operations.OperationGenerator;
import com.continuuity.fabric.operations.WriteOperation;
import com.continuuity.fabric.operations.impl.CompareAndSwap;
import com.continuuity.fabric.operations.impl.Increment;
import com.continuuity.fabric.operations.impl.QueuePop;
import com.continuuity.fabric.operations.impl.QueuePush;
import com.continuuity.fabric.operations.impl.Read;
import com.continuuity.fabric.operations.impl.ReadCounter;
import com.continuuity.fabric.operations.impl.Write;
import com.continuuity.fabric.operations.memory.MemorySimpleOperationExecutor;

/**
 * Simple test of operations stuff.
 */
public class OperationsTest {

  private OperationExecutor executor;

	@Before
	public void setUp() throws Exception {
    MemoryEngine memoryEngine = new MemoryEngine();
    MemorySimpleExecutor memoryExecutor =
        new MemorySimpleExecutor(memoryEngine);
    MemorySimpleOperationExecutor memoryOperationExecutor =
        new MemorySimpleOperationExecutor(memoryExecutor);
    this.executor = memoryOperationExecutor;
	}

	@After
	public void tearDown() throws Exception {
	  this.executor = null;
	}

	@Test
  public void testSimpleReadWrite() throws Exception {
    byte [][] keys = new byte [][] { "key0".getBytes(), "key1".getBytes() };
    byte [][] values = new byte [][] {"value0".getBytes(), "value1".getBytes()};

    List<WriteOperation> writes = new ArrayList<WriteOperation>(2);
    writes.add(new Write(keys[0], values[0]));
    writes.add(new Write(keys[1], values[1]));

    assertTrue(executor.execute(writes));

    Read [] reads = new Read [] {
        new Read(keys[0]), new Read(keys[1]) };

    byte [] value = executor.execute(reads[0]);
    assertEquals(new String(values[0]), new String(value));
    
    value = executor.execute(reads[1]);
    assertEquals(new String(values[1]), new String(value));
  }

	@Test
  public void testCompareAndSwap() throws Exception {
    
    byte [] key = Bytes.toBytes("somekey");
    
    byte [] valueOne = Bytes.toBytes("value_one");
    byte [] valueTwo = Bytes.toBytes("value_two");
    byte [] valueThree = Bytes.toBytes("value_three");
    
    // normal write value one
    executor.execute(new Write(key, valueOne));
    
    // CAS to Two
    assertTrue(executor.execute(new CompareAndSwap(key, valueOne, valueTwo)));
    
    // Read normally, get valueTwo
    assertTrue(Bytes.equals(valueTwo, executor.execute(new Read(key))));
    
    // Bad CAS from One to Two
    assertFalse(executor.execute(new CompareAndSwap(key, valueOne, valueTwo)));
    
    // CAS(key, valueTwo, valueTwo)
    assertTrue(executor.execute(new CompareAndSwap(key, valueTwo, valueTwo)));
    
    // Read normally, get valueTwo
    assertTrue(Bytes.equals(valueTwo, executor.execute(new Read(key))));
    
    // CAS(key, valueTwo, valueThree)
    assertTrue(executor.execute(new CompareAndSwap(key, valueTwo, valueThree)));
    
    // Read normally, get valueThree
    assertTrue(Bytes.equals(valueThree, executor.execute(new Read(key))));
    
    // Bad CAS from null to two
    assertFalse(executor.execute(new CompareAndSwap(key, null, valueTwo)));
    
    // Read normally, get valueThree
    assertTrue(Bytes.equals(valueThree, executor.execute(new Read(key))));
    
    // CAS from three to null
    assertTrue(executor.execute(new CompareAndSwap(key, valueThree, null)));
    
    // Read, should not exist
    assertNull(executor.execute(new Read(key)));
    
    // CAS from null to one
    assertTrue(executor.execute(new CompareAndSwap(key, null, valueOne)));

    // Read normally, get valueOne
    assertTrue(Bytes.equals(valueOne, executor.execute(new Read(key))));
    
    byte [] valueChainKey = Bytes.toBytes("chainkey");
    byte [][] valueChain = generateRandomByteArrays(20, 20); 
    
    // CompareAndSwap the first one, expecting null
    assertTrue(executor.execute(
        new CompareAndSwap(valueChainKey, null, valueChain[0])));
    
    // CAS down the chain
    for (int i=1; i<valueChain.length; i++) {
      assertTrue(executor.execute(
          new CompareAndSwap(valueChainKey, valueChain[i-1], valueChain[i])));
      assertFalse(executor.execute(
          new CompareAndSwap(valueChainKey, valueChain[i-1], valueChain[i])));
    }
    
    // Verify the current value is the last in the chain
    assertTrue(Bytes.equals(valueChain[valueChain.length-1],
        executor.execute(new Read(valueChainKey))));
  }

	@Test
  public void testIncrement() throws Exception {

    
    byte [][] keys = generateRandomByteArrays(10, 8);
    
    // increment first half of keys by 1
    for (int i=0; i<keys.length/2; i++) {
      executor.execute(new Increment(keys[i], 1));
    }
    
    // iterate all keys, only first half should have value of 1, others 0
    for (int i=0; i<keys.length; i++) {
      long count = executor.execute(new ReadCounter(keys[i]));
      if (i < keys.length/2) {
        assertEquals(1L, count);
      } else {
        assertEquals(0L, count);
      }
    }
    
    // decrement first half, everything should be 0
    for (int i=0; i<keys.length/2; i++) {
      executor.execute(new Increment(keys[i], -1));
    }

    for (int i=0; i<keys.length; i++) {
      assertEquals(0L, executor.execute(new ReadCounter(keys[i])));
    }
    
    // increment each by their value of i
    for (int i=0; i<keys.length; i++) {
      executor.execute(new Increment(keys[i], i));
    }
    
    // read them back backwards, expecting their amount to = their position
    for (int i=keys.length-1; i>=0; i--) {
      assertEquals((long)i, executor.execute(new ReadCounter(keys[i])));
    }
    
    // increment each by the total number minus their position
    for (int i=0; i<keys.length; i++) {
      int amount = keys.length - i;
      executor.execute(new Increment(keys[i], amount));
    }
    
    // read them back, all should have the same value of keys.length
    for (int i=0; i<keys.length; i++) {
      assertEquals(keys.length, executor.execute(new ReadCounter(keys[i])));
    }
    
  }

  @Test
  public void testIncrementChain() throws Exception {

    byte [] rawCounterKey = Bytes.toBytes("raw");
    final byte [] stepCounterKey = Bytes.toBytes("step");
    
    // make a generator that increments every 10 increments
    OperationGenerator<Long> generator = new OperationGenerator<Long>() {
      @Override
      public WriteOperation generateWriteOperation(Long amount) {
        if (amount % 10 == 0) return new Increment(stepCounterKey, 10);
        return null;
      }
    };
    
    // increment 9 times, step counter should not exist
    for (int i=0; i<9; i++) {
      Increment increment = new Increment(rawCounterKey, 1);
      increment.setPostIncrementOperationGenerator(generator);
      assertTrue(executor.execute(increment));
      assertEquals(new Long(i+1), increment.getResult());
    }
    
    // raw should be 9, step should be 0
    assertEquals(9L, executor.execute(new ReadCounter(rawCounterKey)));
    assertEquals(0L, executor.execute(new ReadCounter(stepCounterKey)));
    
    // one more and raw should be 10, step should be 1
    Increment increment = new Increment(rawCounterKey, 1);
    increment.setPostIncrementOperationGenerator(generator);
    assertTrue(executor.execute(increment));
    assertEquals(10L, executor.execute(new ReadCounter(rawCounterKey)));
    assertEquals(10L, executor.execute(new ReadCounter(stepCounterKey)));
    
    // 15 more increments
    for (int i=0; i<15; i++) {
      increment = new Increment(rawCounterKey, 1);
      increment.setPostIncrementOperationGenerator(generator);
      assertTrue(executor.execute(increment));
      assertEquals(new Long(i+11), increment.getResult());
    }
    // raw should be 25, step should be 20
    assertEquals(25L, executor.execute(new ReadCounter(rawCounterKey)));
    assertEquals(20L, executor.execute(new ReadCounter(stepCounterKey)));
  }

  @Test
  public void testQueues() throws Exception {

    byte [] queueName = Bytes.toBytes("testQueue");
    byte [][] values = generateRandomByteArrays(10, 10);
    
    // nothing should be in the queue yet
    assertNull(executor.execute(new QueuePop(queueName)));
    
    // push one thing one queue, pop it, then queue empty again
    assertTrue(executor.execute(new QueuePush(queueName, values[0])));
    assertTrue(Bytes.equals(values[0],
        executor.execute(new QueuePop(queueName))));
    assertNull(executor.execute(new QueuePop(queueName)));
    
    // push twice, pop once, push twice, pop three times, then queue empty
    assertTrue(executor.execute(new QueuePush(queueName, values[1])));
    assertTrue(executor.execute(new QueuePush(queueName, values[2])));

    assertTrue(Bytes.equals(values[1],
        executor.execute(new QueuePop(queueName))));
    
    assertTrue(executor.execute(new QueuePush(queueName, values[3])));
    assertTrue(executor.execute(new QueuePush(queueName, values[4])));

    assertTrue(Bytes.equals(values[2],
        executor.execute(new QueuePop(queueName))));
    assertTrue(Bytes.equals(values[3],
        executor.execute(new QueuePop(queueName))));
    assertTrue(Bytes.equals(values[4],
        executor.execute(new QueuePop(queueName))));

    assertNull(executor.execute(new QueuePop(queueName)));
    
    
    // try with a bunch of queues at once
    
    byte [][] queueNames = generateRandomByteArrays(10, 8);
    byte [][] queueValues = generateRandomByteArrays(queueNames.length, 8);
    
    // queues should be empty
    for (byte [] curQueueName : queueNames) {
      assertNull(executor.execute(new QueuePop(curQueueName)));
    }
    
    // add i entries to each queue
    for (int i=0; i<queueNames.length; i++) {
      for (int j=0; j<i; j++) {
        assertTrue(executor.execute(
            new QueuePush(queueNames[i], queueValues[j])));
      }
    }
    
    // each queue should get i pops and then null
    for (int i=0; i<queueNames.length; i++) {
      int numEntriesFound = 0;
      while (true) {
        byte [] value = executor.execute(new QueuePop(queueNames[i]));
        if (value == null) break;
        assertTrue(Bytes.equals(value, queueValues[numEntriesFound]));
        numEntriesFound++;
      }
      assertEquals(i, numEntriesFound);
    }
  }

	@Test @Ignore
  public void testReadModifyWrite() {
    // TODO Implement read-modify-write test
  }

	@Test @Ignore
  public void testOrderedReadWrite() {
    // TODO Implement ordered read-write test
  }
  
  // Private helpers

  private static final Random rand = new Random();

  private byte[][] generateRandomByteArrays(int num, int length) {
    byte [][] bytes = new byte[num][];
    for (int i=0;i<num;i++) {
      bytes[i] = new byte[length];
      rand.nextBytes(bytes[i]);
    }
    return bytes;
  }


  @Test
  public void testSimpleMemoryReadWrite() throws Exception {

    byte [][] keys = new byte [][] { "key0".getBytes(), "key1".getBytes() };
    byte [][] values = new byte [][] {"value0".getBytes(), "value1".getBytes()};

    // Fabric : We are using a MemoryEngine and its NativeSimpleExecutor
    MemoryEngine memoryEngine = new MemoryEngine();
    MemorySimpleExecutor memoryExecutor =
        new MemorySimpleExecutor(memoryEngine);

    // Runner : Create Memory SimpleOperationExecutor using NativeMemorySimpExec
    MemorySimpleOperationExecutor memoryOperationExecutor =
        new MemorySimpleOperationExecutor(memoryExecutor);

    // Client Developer : Make two write operations
    List<WriteOperation> writes = new ArrayList<WriteOperation>(2);
    writes.add(new Write(keys[0], values[0]));
    writes.add(new Write(keys[1], values[1]));

    // Runner : Execute writes through the SimpleMemoryOperationExecutor
    assertTrue(memoryOperationExecutor.execute(writes));
    System.out.println("Wrote two key-values");

    // Client Developer : Make two read operations
    Read [] reads = new Read [] {
        new Read(keys[0]), new Read(keys[1]) };

    // Runner : Execute reads through the SimpleMemoryOperationExecutor
    byte [] value = memoryOperationExecutor.execute(reads[0]);
    assertEquals(new String(values[0]), new String(value));
    System.out.println("Read first key-value");
    value = memoryOperationExecutor.execute(reads[1]);
    assertEquals(new String(values[1]), new String(value));
    System.out.println("Read second key-value");
    
    assertTrue("PURPOSEFUL FAULT INJECTION!!!", true);
  }
}
