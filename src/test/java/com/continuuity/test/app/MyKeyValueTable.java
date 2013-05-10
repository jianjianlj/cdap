package com.continuuity.test.app;

import com.continuuity.api.data.DataSet;
import com.continuuity.api.data.DataSetSpecification;
import com.continuuity.api.data.OperationException;
import com.continuuity.api.data.OperationResult;
import com.continuuity.api.data.dataset.table.Delete;
import com.continuuity.api.data.dataset.table.Increment;
import com.continuuity.api.data.dataset.table.Read;
import com.continuuity.api.data.dataset.table.Swap;
import com.continuuity.api.data.dataset.table.Table;
import com.continuuity.api.data.dataset.table.Write;
import com.continuuity.data.operation.StatusCode;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * This class implements a key/value map on top of Table. Supported
 * operations are read, write, delete, and swap.
 */
public class MyKeyValueTable extends DataSet {

  // the fixed single column to use for the key
  static final byte[] KEY_COLUMN = { 'c' };

  // the underlying table
  private Table table;

  /**
   * Constructor for configuration of key-value table.
   * @param name the name of the table
   */
  public MyKeyValueTable(String name) {
    super(name);
    this.table = new Table("kv_" + name);
  }

  /**
   * Constructor for runtime (@see DataSet#DataSet(DataSetSpecification)).
   * @param spec the data set spec for this data set
   */
  @SuppressWarnings("unused")
  public MyKeyValueTable(DataSetSpecification spec) {
    super(spec);
    this.table = new Table(spec.getSpecificationFor("kv_" + this.getName()));
  }

  @Override
  public DataSetSpecification configure() {
    return new DataSetSpecification.Builder(this).
      dataset(this.table.configure()).create();
  }

  /**
   * Read the value for a given key.
   * @param key the key to read for
   * @return the value for that key, or null if no value was found
   * @throws com.continuuity.api.data.OperationException if the read fails
   */
  @Nullable
  public byte[] read(byte[] key) throws OperationException {
    OperationResult<Map<byte[], byte[]>> result =
      this.table.read(new Read(key, KEY_COLUMN));
    if (result.isEmpty()) {
      return null;
    } else {
      return result.getValue().get(KEY_COLUMN);
    }
  }

  /**
   * Increment the value for a given key and return the resulting value.
   * @param key the key to incrememt
   * @return the incremented value of that key
   * @throws com.continuuity.api.data.OperationException if the increment fails
   */
  public long incrementAndGet(byte[] key, long value) throws OperationException {
    Map<byte[], Long> result =
      this.table.incrementAndGet(new Increment(key, KEY_COLUMN, value));
    Long newValue = result.get(KEY_COLUMN);
    if (newValue == null) {
      throw new OperationException(StatusCode.INTERNAL_ERROR, "Incremented value not part of operation result.");
    } else {
      return newValue;
    }
  }

  /**
   * Write a value to a key.
   * @param key the key
   * @param value the new value
   * @throws com.continuuity.api.data.OperationException if the write fails
   */
  public void write(byte[] key, byte[] value) throws OperationException {
    this.table.write(new Write(key, KEY_COLUMN, value));
  }

  /**
   * Increment the value tof a key. The key must either not exist yet, or its
   * current value must be 8 bytes long to be interpretable as a long.
   * @param key the key
   * @param value the new value
   * @throws com.continuuity.api.data.OperationException if the increment fails
   */
  public void increment(byte[] key, long value) throws OperationException {
    this.table.write(new Increment(key, KEY_COLUMN, value));
  }

  /**
   * Delete a key.
   * @param key the key to delete
   * @throws com.continuuity.api.data.OperationException if the delete fails
   */
  public void delete(byte[] key) throws OperationException {
    this.table.write(new Delete(key, KEY_COLUMN));
  }

  /**
   * Compare the value for key with an expected value, and,
   * if they match, to replace the value with a new value. If they don't
   * match, this operation fails with status code WRITE_CONFLICT.
   *
   * An expected value of null means that the key must not exist. A new value
   * of null means that the key shall be deleted instead of replaced.
   *
   * @param key the key to delete
   * @throws com.continuuity.api.data.OperationException if the swap fails
   */
  public void swap(byte[] key, byte[] oldValue, byte[] newValue) throws OperationException {
    this.table.write(new Swap(key, KEY_COLUMN, oldValue, newValue));
  }
}
