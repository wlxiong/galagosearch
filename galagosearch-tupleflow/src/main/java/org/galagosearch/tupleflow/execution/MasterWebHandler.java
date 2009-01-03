// BSD License (http://www.galagosearch.org/license)

package org.galagosearch.tupleflow.execution;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.galagosearch.tupleflow.execution.JobExecutor.JobExecutionStatus;
import org.mortbay.jetty.handler.AbstractHandler;

/**
 * This handler creates a web interface for checking on the status of a
 * running TupleFlow job.
 *
 * @author trevor
 */
public class MasterWebHandler extends AbstractHandler {
    JobExecutionStatus status;
    Map<CounterName, AggregateCounter> counters = new TreeMap<CounterName, AggregateCounter>();

    public static class CounterName implements Comparable<CounterName> {
        String counterName;
        String stageName;

        public CounterName(String stageName, String counterName) {
            this.stageName = stageName;
            this.counterName = counterName;
        }

        public String getCounterName() { return counterName; }
        public String getStageName() { return stageName; }
        public int compareTo(CounterName other) {
            int result = stageName.compareTo(other.stageName);
            if (result != 0) return result;
            return counterName.compareTo(other.counterName);
        }
    }

    private void handleRefresh(HttpServletRequest request, PrintWriter writer) {
        int refresh = 5;
        if (request.getParameter("refresh") != null) {
            try {
                refresh = Integer.parseInt(request.getParameter("refresh"));
            } catch (Exception e) {
                // do nothing
            }
        }
        if (refresh > 0) {
            writer.append(String.format("<meta http-equiv=\"refresh\" content=\"%d\" />", refresh));
        }
    }

    /**
     * An aggregate counter holds counter data from lots of instances and
     * returns the sum.
     */
    class AggregateCounter {
        /// Returns the total counter value from all instances.
        public synchronized long getValue() {
            return total;
        }

        /// Updates the counter value for a particular instance.
        public synchronized void setValue(String instance, long value) {
            long oldValue = 0;
            if (instances.containsKey(instance))
                oldValue = instances.get(instance);
            long delta = value - oldValue;
            total += delta;
            instances.put(instance, value);
        }

        HashMap<String, Long> instances = new HashMap();
        long total = 0;
    }

    public MasterWebHandler(JobExecutionStatus status) {
        this.status = status;
    }

    public synchronized void handleSetCounter(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        try {
            String instance = request.getParameter("instance");
            String name = request.getParameter("counterName");
            String stageName = request.getParameter("stageName");
            String stringValue = request.getParameter("value");
            Long longValue = new Long(stringValue);

            if (instance == null || stringValue == null || name == null || stageName == null)
                return;

            CounterName fullName = new CounterName(stageName, name);
            if (!counters.containsKey(fullName)) {
                counters.put(fullName, new AggregateCounter());
            }

            counters.get(fullName).setValue(instance, longValue);
        } catch(Exception e) {
            response.sendError(response.SC_NOT_ACCEPTABLE);
        }

        response.setStatus(response.SC_OK);
    }

    private String getElapsed(Date start) {
        long remainingMs = System.currentTimeMillis() - start.getTime();
        long hours = remainingMs / 3600000;
        remainingMs = remainingMs % 3600000;
        long minutes = remainingMs / 60000;
        remainingMs = remainingMs % 60000;
        long seconds = remainingMs / 1000;
        
        return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }

    public synchronized void handleStatus(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        PrintWriter writer = response.getWriter();
        response.setContentType("text/html");

        Map<String, StageExecutionStatus> stagesStatus = status.getStageStatus();

        writer.append("<html>");
        handleRefresh(request, writer);
        writer.append("<head>\n");
        writer.append("<style type=\"text/css\">\n");

        writer.append("table { border-collapse: collapse; }\n");
        writer.append("tr.blocked td { background: #BBB; }\n");
        writer.append("tr.running td { background: #8D8; }\n");
        writer.append("tr.complete td { background: #5A5; }\n");
        writer.append("td { padding: 5px; }\n");
        writer.append("td.right { text-align: right; }\n");
        writer.append("</style>");
        writer.append("</head>\n");
        writer.append("<body>\n");
        writer.append("<font size=\"-3\">Refresh: <a href=\"/?refresh=1\">1 second</a> " +
                               "<a href=\"/?refresh=5\">5 seconds</a> " +
                               "<a href=\"/?refresh=15\">15 seconds</a> " +
                               "<a href=\"/?refresh=60\">1 minute</a> " +
                               "<a href=\"/?refresh=-1\">never</a></font><br/>" );

        // The first table contains format information:
        writer.append("<table>");
        writer.append(String.format("<tr><td>Start</td><td>%s</td></tr>\n", status.getStartDate().toString()));
        writer.append(String.format("<tr><td>Elapsed</td><td>%s</td></tr>\n",
                getElapsed(status.getStartDate())));
        writer.append(String.format("<tr><td>Max memory</td><td>%dM</td></tr>\n",
                status.getMaxMemory()/1048576));
        writer.append(String.format("<tr><td>Free memory</td><td>%dM</td></tr>\n",
                status.getFreeMemory()/1048576));
        writer.append("</table>");
        // Two-column table, stage data on left, counters on right
        writer.append("<table><tr><td>\n");
        writer.append("<table>\n");
        writer.append(String.format("<tr><th>%s</th><th>%s</th><th>%s</th><th>%s</th><th>%s</th></tr>\n",
                                    "Stage", "Blocked", "Queued", "Running", "Completed"));
        for (Entry<String, StageExecutionStatus> entry : stagesStatus.entrySet()) {
            StageExecutionStatus stageStatus = entry.getValue();

            if (stageStatus.getBlockedInstances() > 0) {
                writer.append("<tr class=\"blocked\">");
            } else if (stageStatus.getQueuedInstances() + stageStatus.getRunningInstances() > 0) {
                writer.append("<tr class=\"running\">");
            } else {
                writer.append("<tr class=\"complete\">");
            }

            writer.append("<td>" + entry.getKey() + "</td>");
            writer.append("<td class=\"right\">" + stageStatus.getBlockedInstances() + "</td>");
            writer.append("<td class=\"right\">" + stageStatus.getQueuedInstances() + "</td>");
            writer.append("<td class=\"right\">" + stageStatus.getRunningInstances() + "</td>");
            writer.append("<td class=\"right\">" + stageStatus.getCompletedInstances() + "</td>");
            writer.append("</tr>");
        }
        writer.append("</table>"); // end stage table

        // Now, print counter data:
        writer.append("</td><td>\n");
        writer.append("<table>");
        writer.append("<tr><th>Stage</th><th>Counter</th><th>Value</th></tr>");
        for (Entry<CounterName, AggregateCounter> entry : this.counters.entrySet()) {
            if (entry.getValue().getValue() == 0) continue;
            String stageName = entry.getKey().getStageName();
            StageExecutionStatus stageStatus = stagesStatus.get(stageName);

            if (stageStatus != null &&
                stageStatus.getRunningInstances() + stageStatus.getQueuedInstances() > 0) {
                writer.append("<tr class=\"running\">");
            } else {
                writer.append("<tr>");
            }
            writer.append("<td>" + entry.getKey().getStageName() + "</td>");
            writer.append("<td>" + entry.getKey().getCounterName() + "</td>");
            writer.append("<td>" + entry.getValue().getValue() + "</td>");
            writer.append("</tr>");
        }

        writer.append("</table>"); // end counter table
        writer.append("</td></tr></table>\n"); // end two-column table
        writer.append("</body>");
        writer.append("</html>");
        writer.close();
    }
    
    public synchronized void handle(
            String target,
            HttpServletRequest request,
            HttpServletResponse response,
            int dispatch)
            throws IOException, ServletException {
        if (request.getPathInfo().equals("/setcounter")) {
            handleSetCounter(request, response);
        } else {
            handleStatus(request, response);
        }
    }
}
