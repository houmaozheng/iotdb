/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.qp.physical.crud;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.metadata.PathNotExistException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.logical.Operator;
import org.apache.iotdb.db.qp.logical.Operator.OperatorType;
import org.apache.iotdb.db.utils.CommonUtils;
import org.apache.iotdb.db.utils.TestOnly;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.utils.Binary;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;
import org.apache.iotdb.tsfile.utils.TsPrimitiveType;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InsertRowPlan extends InsertPlan {

  private static final Logger logger = LoggerFactory.getLogger(InsertRowPlan.class);
  private static final short TYPE_RAW_STRING = -1;

  private long time;
  private Object[] values;

  // if isNeedInferType is true, the values must be String[], so we could infer types from them
  // if values is object[], we could use the raw type of them, and we should set this to false
  private boolean isNeedInferType = false;

  public InsertRowPlan() {
    super(OperatorType.INSERT);
  }

  @TestOnly
  public InsertRowPlan(String deviceId, long insertTime, String[] measurements,
      TSDataType[] dataTypes, String[] insertValues) {
    super(OperatorType.INSERT);
    this.time = insertTime;
    this.deviceId = deviceId;
    this.measurements = measurements;
    this.dataTypes = dataTypes;
    this.values = new Object[measurements.length];
    for (int i = 0; i < measurements.length; i++) {
      try {
        values[i] = CommonUtils.parseValueForTest(dataTypes[i], insertValues[i]);
      } catch (QueryProcessException e) {
        e.printStackTrace();
      }
    }
  }

  @TestOnly
  public InsertRowPlan(String deviceId, long insertTime, String measurement, TSDataType type,
      String insertValue) {
    super(OperatorType.INSERT);
    this.time = insertTime;
    this.deviceId = deviceId;
    this.measurements = new String[]{measurement};
    this.dataTypes = new TSDataType[]{type};
    this.values = new Object[1];
    try {
      values[0] = CommonUtils.parseValueForTest(dataTypes[0], insertValue);
    } catch (QueryProcessException e) {
      e.printStackTrace();
    }
  }

  public InsertRowPlan(TSRecord tsRecord) {
    super(OperatorType.INSERT);
    this.deviceId = tsRecord.deviceId;
    this.time = tsRecord.time;
    this.measurements = new String[tsRecord.dataPointList.size()];
    this.schemas = new MeasurementSchema[tsRecord.dataPointList.size()];
    this.dataTypes = new TSDataType[tsRecord.dataPointList.size()];
    this.values = new Object[tsRecord.dataPointList.size()];
    for (int i = 0; i < tsRecord.dataPointList.size(); i++) {
      measurements[i] = tsRecord.dataPointList.get(i).getMeasurementId();
      schemas[i] = new MeasurementSchema(measurements[i], tsRecord.dataPointList.get(i).getType(),
          TSEncoding.PLAIN);
      dataTypes[i] = tsRecord.dataPointList.get(i).getType();
      values[i] = tsRecord.dataPointList.get(i).getValue();
    }
  }

  public InsertRowPlan(String deviceId, long insertTime, String[] measurementList,
      TSDataType[] dataTypes, Object[] insertValues) {
    super(Operator.OperatorType.INSERT);
    this.time = insertTime;
    this.deviceId = deviceId;
    this.measurements = measurementList;
    this.dataTypes = dataTypes;
    this.values = insertValues;
  }

  public InsertRowPlan(String deviceId, long insertTime, String[] measurementList,
      String[] insertValues) {
    super(Operator.OperatorType.INSERT);
    this.time = insertTime;
    this.deviceId = deviceId;
    this.measurements = measurementList;
    this.dataTypes = new TSDataType[measurements.length];
    // We need to create an Object[] for the data type casting, because we can not set Float, Long to String[i]
    this.values = new Object[measurements.length];
    System.arraycopy(insertValues, 0, values, 0, measurements.length);
    isNeedInferType = true;
  }


  public long getTime() {
    return time;
  }

  public void setTime(long time) {
    this.time = time;
  }

  public boolean isNeedInferType() {
    return isNeedInferType;
  }

  public void setNeedInferType(boolean inferType) {
    this.isNeedInferType = inferType;
  }

  /**
   * if inferType is true, transfer String[] values to specific data types (Integer, Long, Float,
   * Double, Binary)
   */
  public void setSchemasAndTransferType(MeasurementSchema[] schemas) throws QueryProcessException {
    this.schemas = schemas;
    if (isNeedInferType) {
      for (int i = 0; i < schemas.length; i++) {
        if (schemas[i] == null) {
          if (IoTDBDescriptor.getInstance().getConfig().isEnablePartialInsert()) {
            markFailedMeasurementInsertion(i);
          } else {
            throw new QueryProcessException(new PathNotExistException(
                deviceId + IoTDBConstant.PATH_SEPARATOR + measurements[i]));
          }
          continue;
        }
        dataTypes[i] = schemas[i].getType();
        try {
          values[i] = CommonUtils.parseValue(dataTypes[i], values[i].toString());
        } catch (Exception e) {
          logger.warn("{}.{} data type is not consistent, input {}, registered {}", deviceId,
              measurements[i], values[i], dataTypes[i]);
          if (IoTDBDescriptor.getInstance().getConfig().isEnablePartialInsert()) {
            markFailedMeasurementInsertion(i);
            schemas[i] = null;
          } else {
            throw e;
          }
        }
      }
    }
  }

  @Override
  public void markFailedMeasurementInsertion(int index) {
    super.markFailedMeasurementInsertion(index);
    values[index] = null;
  }

  @Override
  public List<Path> getPaths() {
    List<Path> ret = new ArrayList<>();

    for (String m : measurements) {
      ret.add(new Path(deviceId, m));
    }
    return ret;
  }

  public Object[] getValues() {
    return this.values;
  }

  public void setValues(Object[] values) {
    this.values = values;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    InsertRowPlan that = (InsertRowPlan) o;
    return time == that.time && Objects.equals(deviceId, that.deviceId)
        && Arrays.equals(measurements, that.measurements)
        && Arrays.equals(values, that.values);
  }

  @Override
  public int hashCode() {
    return Objects.hash(deviceId, time);
  }

  @Override
  public void serialize(DataOutputStream stream) throws IOException {
    int type = PhysicalPlanType.INSERT.ordinal();
    stream.writeByte((byte) type);
    stream.writeLong(time);

    putString(stream, deviceId);

    stream.writeInt(
        measurements.length - (failedMeasurements == null ? 0 : failedMeasurements.size()));

    for (String m : measurements) {
      if (m != null) {
        putString(stream, m);
      }
    }

    try {
      putValues(stream);
    } catch (QueryProcessException e) {
      throw new IOException(e);
    }

    // the types are not inferred before the plan is serialized
    stream.write((byte) (isNeedInferType ? 1 : 0));
  }

  private void putValues(DataOutputStream outputStream) throws QueryProcessException, IOException {
    for (int i = 0; i < values.length; i++) {
      // types are not determined, the situation mainly occurs when the plan uses string values
      // and is forwarded to other nodes
      if (dataTypes == null || dataTypes[i] == null) {
        ReadWriteIOUtils.write(TYPE_RAW_STRING, outputStream);
        ReadWriteIOUtils.write((String) values[i], outputStream);
        continue;
      }

      ReadWriteIOUtils.write(dataTypes[i], outputStream);
      switch (dataTypes[i]) {
        case BOOLEAN:
          ReadWriteIOUtils.write((Boolean) values[i], outputStream);
          break;
        case INT32:
          ReadWriteIOUtils.write((Integer) values[i], outputStream);
          break;
        case INT64:
          ReadWriteIOUtils.write((Long) values[i], outputStream);
          break;
        case FLOAT:
          ReadWriteIOUtils.write((Float) values[i], outputStream);
          break;
        case DOUBLE:
          ReadWriteIOUtils.write((Double) values[i], outputStream);
          break;
        case TEXT:
          ReadWriteIOUtils.write((Binary) values[i], outputStream);
          break;
        default:
          throw new QueryProcessException("Unsupported data type:" + dataTypes[i]);
      }
    }
  }

  private void putValues(ByteBuffer buffer) throws QueryProcessException {
    for (int i = 0; i < values.length; i++) {
      // types are not determined, the situation mainly occurs when the plan uses string values
      // and is forwarded to other nodes
      if (dataTypes == null || dataTypes[i] == null) {
        ReadWriteIOUtils.write(TYPE_RAW_STRING, buffer);
        ReadWriteIOUtils.write((String) values[i], buffer);
        continue;
      }

      ReadWriteIOUtils.write(dataTypes[i], buffer);
      switch (dataTypes[i]) {
        case BOOLEAN:
          ReadWriteIOUtils.write((Boolean) values[i], buffer);
          break;
        case INT32:
          ReadWriteIOUtils.write((Integer) values[i], buffer);
          break;
        case INT64:
          ReadWriteIOUtils.write((Long) values[i], buffer);
          break;
        case FLOAT:
          ReadWriteIOUtils.write((Float) values[i], buffer);
          break;
        case DOUBLE:
          ReadWriteIOUtils.write((Double) values[i], buffer);
          break;
        case TEXT:
          ReadWriteIOUtils.write((Binary) values[i], buffer);
          break;
        default:
          throw new QueryProcessException("Unsupported data type:" + dataTypes[i]);
      }
    }
  }

  /**
   * Make sure the values is already inited before calling this
   */
  public void fillValues(ByteBuffer buffer) throws QueryProcessException {
    for (int i = 0; i < measurements.length; i++) {
      // types are not determined, the situation mainly occurs when the plan uses string values
      // and is forwarded to other nodes
      short typeNum = ReadWriteIOUtils.readShort(buffer);
      if (typeNum == TYPE_RAW_STRING) {
        values[i] = ReadWriteIOUtils.readString(buffer);
        continue;
      }

      dataTypes[i] = TSDataType.values()[typeNum];
      switch (dataTypes[i]) {
        case BOOLEAN:
          values[i] = ReadWriteIOUtils.readBool(buffer);
          break;
        case INT32:
          values[i] = ReadWriteIOUtils.readInt(buffer);
          break;
        case INT64:
          values[i] = ReadWriteIOUtils.readLong(buffer);
          break;
        case FLOAT:
          values[i] = ReadWriteIOUtils.readFloat(buffer);
          break;
        case DOUBLE:
          values[i] = ReadWriteIOUtils.readDouble(buffer);
          break;
        case TEXT:
          values[i] = ReadWriteIOUtils.readBinary(buffer);
          break;
        default:
          throw new QueryProcessException("Unsupported data type:" + dataTypes[i]);
      }
    }
  }

  @Override
  public void serialize(ByteBuffer buffer) {
    int type = PhysicalPlanType.INSERT.ordinal();
    buffer.put((byte) type);
    buffer.putLong(time);

    putString(buffer, deviceId);

    buffer
        .putInt(measurements.length - (failedMeasurements == null ? 0 : failedMeasurements.size()));

    for (String measurement : measurements) {
      if (measurement != null) {
        putString(buffer, measurement);
      }
    }

    try {
      putValues(buffer);
    } catch (QueryProcessException e) {
      e.printStackTrace();
    }

    // the types are not inferred before the plan is serialized
    buffer.put((byte) (isNeedInferType ? 1 : 0));
  }

  @Override
  public void deserialize(ByteBuffer buffer) {
    this.time = buffer.getLong();
    this.deviceId = readString(buffer);

    int measurementSize = buffer.getInt();

    this.measurements = new String[measurementSize];
    for (int i = 0; i < measurementSize; i++) {
      measurements[i] = readString(buffer);
    }

    this.dataTypes = new TSDataType[measurementSize];
    this.values = new Object[measurementSize];
    try {
      fillValues(buffer);
    } catch (QueryProcessException e) {
      e.printStackTrace();
    }

    isNeedInferType = buffer.get() == 1;
  }

  @Override
  public String toString() {
    return "deviceId: " + deviceId + ", time: " + time;
  }

  public TimeValuePair composeTimeValuePair(int measurementIndex) {
    if (measurementIndex >= values.length) {
      return null;
    }
    Object value = values[measurementIndex];
    return new TimeValuePair(time,
        TsPrimitiveType.getByType(schemas[measurementIndex].getType(), value));
  }
}