package com.github.ambry.clustermap;


import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Disk stores {@link Replica}s. Each Disk is hosted on one specific {@link DataNode}. Each Disk is uniquely
 * identified by its DataNode and mount path (the path to this Disk's device on its DataNode).
 */
public class Disk implements DiskId {
  // Hard-code disk capacity limits in bytes for validation
  private static final long MinCapacityInBytes = 10 * 1024 * 1024 * 1024L;
  private static final long MaxCapacityInBytes = 1024 * 1024 * 1024 * 1024L; // 1 PB

  private final DataNode dataNode;
  private String mountPath;
  private HardwareState hardwareState;
  private long capacityInBytes;

  private final Logger logger = LoggerFactory.getLogger(getClass());

  public Disk(DataNode dataNode, JSONObject jsonObject) throws JSONException {
    this.dataNode = dataNode;
    this.mountPath = jsonObject.getString("mountPath");
    this.hardwareState = HardwareState.valueOf(jsonObject.getString("hardwareState"));
    this.capacityInBytes = jsonObject.getLong("capacityInBytes");

    validate();
  }

  @Override
  public String getMountPath() {
    return mountPath;
  }

  @Override
  public HardwareState getState() {
    // A Disk is unavailable if its DataNode is unavailable.
    if (dataNode.getState() == HardwareState.UNAVAILABLE) {
      return HardwareState.UNAVAILABLE;
    }
    return hardwareState;
  }

  @Override
  public long getCapacityInBytes() {
    return capacityInBytes;
  }

  public DataNode getDataNode() {
    return dataNode;
  }

  public HardwareState getHardwareState() {
    return hardwareState;
  }

  protected void validateDataNode() {
    if (dataNode == null) {
      throw new IllegalStateException("DataNode cannot be null.");
    }
  }

  protected void validateMountPath() {
    if (mountPath == null) {
      throw new IllegalStateException("Mount path cannot be null.");
    }
    if (mountPath.length() == 0) {
      throw new IllegalStateException("Mount path cannot be zero-length string.");
    }
  }

  protected void validateCapacity() {
    if (capacityInBytes < MinCapacityInBytes) {
      throw new IllegalStateException("Invalid disk capacity: " + capacityInBytes + " is less than " +
                                      MinCapacityInBytes);
    }
    else if (capacityInBytes > MaxCapacityInBytes) {
      throw new IllegalStateException("Invalid disk capacity: " + capacityInBytes + " is more than " +
                                      MaxCapacityInBytes);
    }
  }

  protected void validate() {
    logger.trace("begin validate.");
    validateDataNode();
    validateMountPath();
    validateCapacity();
    logger.trace("complete validate.");
  }

  public JSONObject toJSONObject() throws JSONException {
    return new JSONObject()
            .put("mountPath", mountPath)
            .put("hardwareState", hardwareState)
            .put("capacityInBytes", capacityInBytes);
  }

  @Override
  public String toString() {
    return "Disk: " + dataNode.getHostname() + ":" + dataNode.getPort() + ":" + getMountPath();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Disk disk = (Disk)o;

    if (!dataNode.equals(disk.dataNode)) return false;
    return mountPath.equals(disk.mountPath);
  }

  @Override
  public int hashCode() {
    int result = dataNode.hashCode();
    result = 31 * result + mountPath.hashCode();
    return result;
  }
}