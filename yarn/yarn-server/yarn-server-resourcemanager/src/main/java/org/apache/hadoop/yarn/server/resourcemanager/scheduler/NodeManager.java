/**
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
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.api.records.NodeId;
import org.apache.hadoop.yarn.server.resourcemanager.resourcetracker.NodeInfo;

/**
 * This class is used by ClusterInfo to keep track of all the applications/containers
 * running on a node.
 *
 */
@Private
@Unstable
public class NodeManager implements NodeInfo {
  private static final Log LOG = LogFactory.getLog(NodeManager.class);
  private static final RecordFactory recordFactory = RecordFactoryProvider.getRecordFactory(null);
  private final NodeId nodeId;
  private final String hostName;
  private Resource totalCapability;
  private Resource availableResource = recordFactory.newRecordInstance(Resource.class);
  private Resource usedResource = recordFactory.newRecordInstance(Resource.class);
  private final Node node;
  
  
  private static final Container[] EMPTY_CONTAINER_ARRAY = new Container[] {};
  private static final List<Container> EMPTY_CONTAINER_LIST = Arrays.asList(EMPTY_CONTAINER_ARRAY);
  private static final ApplicationId[] EMPTY_APPLICATION_ARRAY = new ApplicationId[]{};
  private static final List<ApplicationId> EMPTY_APPLICATION_LIST = Arrays.asList(EMPTY_APPLICATION_ARRAY);
  
  public static final String ANY = "*";  
  /* set of containers that are allocated containers */
  private final Map<ContainerId, Container> allocatedContainers = 
    new TreeMap<ContainerId, Container>();
    
  /* set of containers that are currently active on a node manager */
  private final Map<ContainerId, Container> activeContainers =
    new TreeMap<ContainerId, Container>();
  
  /* set of containers that need to be cleaned */
  private final Set<Container> containersToClean = 
    new TreeSet<Container>(new org.apache.hadoop.yarn.server.resourcemanager.resource.Container.Comparator());

  
  /* the list of applications that have finished and need to be purged */
  private final List<ApplicationId> finishedApplications = new ArrayList<ApplicationId>();
  
  private volatile int numContainers;
  
  public NodeManager(NodeId nodeId, String hostname, 
      Node node, Resource capability) {
    this.nodeId = nodeId;   
    this.totalCapability = capability; 
    this.hostName = hostname;
    org.apache.hadoop.yarn.server.resourcemanager.resource.Resource.addResource(
        availableResource, capability);
    this.node = node;
  }

  /**
   * NodeInfo for this node.
   * @return the {@link NodeInfo} for this node.
   */
  public NodeInfo getNodeInfo() {
    return this;
  }
  
  /**
   * The Scheduler has allocated containers on this node to the 
   * given application.
   * 
   * @param applicationId application
   * @param containers allocated containers
   */
  public synchronized void allocateContainer(ApplicationId applicationId, 
      List<Container> containers) {
    if (containers == null) {
      LOG.error("Adding null containers for application " + applicationId);
      return;
    }   
    for (Container container : containers) {
      allocateContainer(container);
    }

    LOG.info("addContainers:" +
        " node=" + getHostName() + 
        " #containers=" + containers.size() + 
        " available=" + getAvailableResource().getMemory() + 
        " used=" + getUsedResource().getMemory());
  }

  /**
   * Status update from the NodeManager
   * @param nodeStatus node status
   * @return the set of containers no longer should be used by the
   * node manager.
   */
  public synchronized NodeResponse 
    statusUpdate(Map<String,List<Container>> allContainers) {

    if (allContainers == null) {
      return new NodeResponse(EMPTY_APPLICATION_LIST, EMPTY_CONTAINER_LIST,
          EMPTY_CONTAINER_LIST);
    }
       
    List<Container> listContainers = new ArrayList<Container>();
    // Iterate through the running containers and update their status
    for (Map.Entry<String, List<Container>> e : 
      allContainers.entrySet()) {
      listContainers.addAll(e.getValue());
    }
    NodeResponse statusCheck = update(listContainers);
    return statusCheck;
  }
  
  /**
   * Status update for an application running on a given node
   * @param node node
   * @param containers containers update.
   * @return containers that are completed or need to be preempted.
   */
  private synchronized NodeResponse update(List<Container> containers) {
    List<Container> completedContainers = new ArrayList<Container>();
    List<Container> containersToCleanUp = new ArrayList<Container>();
    List<ApplicationId> lastfinishedApplications = new ArrayList<ApplicationId>();
    
    for (Container container : containers) {
      if (allocatedContainers.remove(container.getId()) != null) {
        activeContainers.put(container.getId(), container);
        LOG.info("Activated container " + container.getId() + " on node " + 
         getHostName());
      }

      if (container.getState() == ContainerState.COMPLETE) {
        if (activeContainers.remove(container.getId()) != null) {
          updateResource(container);
          LOG.info("Completed container " + container);
        }
        completedContainers.add(container);
        LOG.info("Removed completed container " + container.getId() + " on node " + 
            getHostName());
      }
      else if (container.getState() != ContainerState.COMPLETE && 
          (!allocatedContainers.containsKey(container.getId())) && 
          !activeContainers.containsKey(container.getId())) {
        containersToCleanUp.add(container);
      }
    }
    containersToCleanUp.addAll(containersToClean);
    /* clear out containers to clean */
    containersToClean.clear();
    lastfinishedApplications.addAll(finishedApplications);
    return new NodeResponse(lastfinishedApplications, completedContainers, 
        containersToCleanUp);
  }
  
  private synchronized void allocateContainer(Container container) {
    deductAvailableResource(container.getResource());
    ++numContainers;
    
    allocatedContainers.put(container.getId(), container);
    LOG.info("Allocated container " + container.getId() + 
        " to node " + getHostName());
    
    LOG.info("Assigned container " + container.getId() + 
        " of capacity " + container.getResource() + " on host " + getHostName() + 
        ", which currently has " + numContainers + " containers, " + 
        getUsedResource() + " used and " + 
        getAvailableResource() + " available");
  }

  private synchronized boolean isValidContainer(Container c) {    
    if (activeContainers.containsKey(c.getId()) || allocatedContainers.containsKey(c.getId()))
      return true;
    return false;
  }

  private synchronized void updateResource(Container container) {
    addAvailableResource(container.getResource());
    --numContainers;
  }
  
  /**
   * Release an allocated container on this node.
   * @param container container to be released
   * @return <code>true</code> iff the container was unused, 
   *         <code>false</code> otherwise
   */
  public synchronized boolean releaseContainer(Container container) {
    if (!isValidContainer(container)) {
      LOG.error("Invalid container released " + container);
      return false;
    }
    
    /* remove the containers from the nodemanger */
    
    // Was this container launched?
    activeContainers.remove(container.getId());
    allocatedContainers.remove(container.getId());
    containersToClean.add(container);
    updateResource(container);

    LOG.info("Released container " + container.getId() + 
        " of capacity " + container.getResource() + " on host " + getHostName() + 
        ", which currently has " + numContainers + " containers, " + 
        getUsedResource() + " used and " + getAvailableResource()
        + " available" + ", release resources=" + true);
    return true;
  }

  @Override
  public NodeId getNodeID() {
    return this.nodeId;
  }

  @Override
  public String getHostName() {
    return this.hostName;
  }

  @Override
  public Resource getTotalCapability() {
   return this.totalCapability;
  }

  @Override
  public String getRackName() {
    return node.getNetworkLocation();
  }

  @Override
  public Node getNode() {
    return this.node;
  }

  @Override
  public synchronized Resource getAvailableResource() {
    return this.availableResource;
  }

  @Override
  public synchronized Resource getUsedResource() {
    return this.usedResource;
  }

  public synchronized void addAvailableResource(Resource resource) {
    if (resource == null) {
      LOG.error("Invalid resource addition of null resource for " + this.hostName);
      return;
    }
    org.apache.hadoop.yarn.server.resourcemanager.resource.Resource.addResource(
        availableResource, resource);
    org.apache.hadoop.yarn.server.resourcemanager.resource.Resource.subtractResource(
        usedResource, resource);
  }

  public synchronized void deductAvailableResource(Resource resource) {
    if (resource == null) {
      LOG.error("Invalid deduction of null resource for "+ this.hostName);
    }
    org.apache.hadoop.yarn.server.resourcemanager.resource.Resource.subtractResource(
        availableResource, resource);
    org.apache.hadoop.yarn.server.resourcemanager.resource.Resource.addResource(
        usedResource, resource);
  }

  public synchronized void notifyFinishedApplication(ApplicationId applicationId) {  
    finishedApplications.add(applicationId);
    /* make sure to iterate through the list and remove all the containers that 
     * belong to this application.
     */
  }

  @Override
  public int getNumContainers() {
    return numContainers;
  }
  
  @Override
  public String toString() {
    return "host: " + getHostName() + " #containers=" + getNumContainers() +  
      " available=" + getAvailableResource().getMemory() + 
      " used=" + getUsedResource().getMemory();
  }
 }