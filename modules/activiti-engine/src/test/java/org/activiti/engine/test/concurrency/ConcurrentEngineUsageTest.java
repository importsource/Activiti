/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.engine.test.concurrency;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.activiti.engine.impl.history.HistoryLevel;
import org.activiti.engine.impl.identity.Authentication;
import org.activiti.engine.impl.test.PluggableActivitiTestCase;
import org.activiti.engine.task.Task;
import org.activiti.engine.test.Deployment;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Test that uses a number of threads to start processes and complete tasks concurrently.
 * 
 * @author Frederik Heremans
 */
public class ConcurrentEngineUsageTest extends PluggableActivitiTestCase {

  private static Logger log = LoggerFactory.getLogger(ConcurrentEngineUsageTest.class);
  
  @Deployment
  public void testConcurrentUsage() throws Exception {
    
    if(!processEngineConfiguration.getDatabaseType().equals("h2")) {
      int numberOfThreads = 5;
      int numberOfProcessesPerThread = 5;
      int totalNumberOfTasks = 2 * numberOfThreads * numberOfProcessesPerThread;
      
      ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 10, 1000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(numberOfThreads));
      
      for(int i=0; i< numberOfThreads; i++) {
        executor.execute(new ConcurrentProcessRunnerRunnable(numberOfProcessesPerThread, "kermit" + i));
      }
      
      // Wait for termination or timeout and check if all tasks are complete
      executor.shutdown();
      executor.awaitTermination(20000, TimeUnit.MILLISECONDS);
      assertEquals(0, executor.getActiveCount());
      
      // Check there are no processes active anymore
      assertEquals(0, runtimeService.createProcessInstanceQuery().count());
      
      if(processEngineConfiguration.getHistoryLevel().isAtLeast(HistoryLevel.ACTIVITY)) {
        // Check if all processes and tasks are complete
        assertEquals(numberOfProcessesPerThread * numberOfThreads, historyService.createHistoricProcessInstanceQuery()
                .finished().count());
        assertEquals(totalNumberOfTasks, historyService.createHistoricTaskInstanceQuery()
                .finished().count());
      }
    }
  }
  
  protected void retryStartProcess(String runningUser) {
    int retries = 5;
    int timeout = 200;
    boolean success = false;
    while(retries > 0 && !success) {
      try {
        runtimeService.startProcessInstanceByKey("concurrentProcess", Collections.singletonMap("assignee", (Object)runningUser));
        success = true;
      } catch(PersistenceException pe) {
        retries = retries - 1;
        log.debug("Retrying process start: " + pe.getMessage());
        try {
          Thread.sleep(timeout);
        } catch (InterruptedException ignore) {
        }
        timeout = timeout + 200;
      }
    }
  }
  
  protected void retryFinishTask(String taskId) {
    int retries = 5;
    int timeout = 200;
    boolean success = false;
    while(retries > 0 && !success) {
      try {
        taskService.complete(taskId);
        success = true;
      } catch(PersistenceException pe) {
        retries = retries - 1;
        log.debug("Retrying task completion: " + pe.getMessage());
        try {
          Thread.sleep(timeout);
        } catch (InterruptedException ignore) {
        }
        timeout = timeout + 200;
      }
    }
  }
  
  
  private class ConcurrentProcessRunnerRunnable implements Runnable {
    private String drivingUser;
    private int numberOfProcesses;
    
    public ConcurrentProcessRunnerRunnable(int numberOfProcesses, String drivingUser) {
      this.drivingUser = drivingUser;
      this.numberOfProcesses = numberOfProcesses;
    }

    @Override
    public void run() {
      Authentication.setAuthenticatedUserId(drivingUser);
      
      boolean finishTask = false;
      boolean tasksAvailable = false;
      
      while(numberOfProcesses > 0 || tasksAvailable)
      {
        if(numberOfProcesses > 0 && !finishTask) {
          // Start a new process
          retryStartProcess(drivingUser);
          finishTask = true;
          
          if(numberOfProcesses == 0) {
            // Make sure while-loop doesn't stop when processes are all started
            tasksAvailable = taskService.createTaskQuery().taskAssignee(drivingUser).count() > 0;
          }
          numberOfProcesses = numberOfProcesses - 1;
        } else {
          // Finish a task
          List<Task> taskToComplete = taskService.createTaskQuery().taskAssignee(drivingUser).listPage(0, 1);
          tasksAvailable = taskToComplete.size() > 0;
          if(tasksAvailable) {
            retryFinishTask(taskToComplete.get(0).getId());
          }
          finishTask = false;
        }
      }
    }
  }
}
