/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.steps;

import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Marks a flow build as entering a gated “segment”, like a stage in a pipeline.
 * Each job has a set of named segments, each of which acts like a semaphore with an initial permit count,
 * but with the special behavior that only one build may be waiting at any time: the newest.
 * Credit goes to @jtnord for implementing the {@code block} operator in {@code buildflow-extensions}, which inspired this.
 */
public class SegmentStep extends Step {
    
    private static final Logger LOGGER = Logger.getLogger(SegmentStep.class.getName());

    public final String name;
    private final int concurrency;

    private SegmentStep(String name, int concurrency) {
        if (name == null) {
            throw new IllegalArgumentException("must specify name");
        }
        this.name = name;
        this.concurrency = concurrency;
    }

    @DataBoundConstructor public SegmentStep(String name, String concurrency) {
        this(Util.fixEmpty(name), Util.fixEmpty(concurrency) != null ? Integer.parseInt(concurrency) : Integer.MAX_VALUE);
    }

    public String getConcurrency() {
        return concurrency == Integer.MAX_VALUE ? "" : Integer.toString(concurrency);
    }

    @Override public boolean start(StepContext context) throws Exception {
        FlowNode n = context.get(FlowNode.class);
        n.addAction(new LabelAction(name));
        Run<?,?> r = context.get(Run.class);
        enter(r, context, name, concurrency);
        return false;
    }

    private static XmlFile getConfigFile() {
        return new XmlFile(new File(Jenkins.getInstance().getRootDir(), SegmentStep.class.getName() + ".xml"));
    }

    private static Map<String,Map<String,Segment>> segmentsByNameByJob;

    @SuppressWarnings("unchecked")
    private static synchronized void load() {
        if (segmentsByNameByJob == null) {
            segmentsByNameByJob = new TreeMap<String,Map<String,Segment>>();
            XmlFile configFile = getConfigFile();
            if (configFile.exists()) {
                try {
                    segmentsByNameByJob = (Map<String,Map<String,Segment>>) configFile.read();
                } catch (IOException x) {
                    LOGGER.log(Level.WARNING, null, x);
                }
            }
        }
    }

    private static synchronized void save() {
        try {
            getConfigFile().write(segmentsByNameByJob);
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
        }
    }

    private static synchronized void enter(Run<?,?> r, StepContext context, String name, int concurrency) {
        load();
        Job<?,?> job = r.getParent();
        String jobName = job.getFullName();
        Map<String,Segment> segmentsByName = segmentsByNameByJob.get(jobName);
        if (segmentsByName == null) {
            segmentsByName = new TreeMap<String,Segment>();
            segmentsByNameByJob.put(jobName, segmentsByName);
        }
        Segment segment = segmentsByName.get(name);
        if (segment == null) {
            segment = new Segment();
            segmentsByName.put(name, segment);
        }
        segment.concurrency = concurrency;
        int build = r.number;
        if (segment.waitingContext != null) {
            // Someone has got to give up.
            if (segment.waitingBuild < build) {
                // Cancel the older one.
                try {
                    cancel(segment.waitingContext.get(FlowExecution.class), segment.waitingContext.get(Run.class));
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "could not cancel an older flow (perhaps since deleted?)", x);
                }
            } else if (segment.waitingBuild > build) {
                // Cancel this one. And work with the older one below, instead of the one initiating this call.
                try {
                    cancel(context.get(FlowExecution.class), r);
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "could not cancel the current flow", x);
                }
                build = segment.waitingBuild;
                context = segment.waitingContext;
            } else {
                throw new IllegalStateException("the same flow is trying to reënter the segment " + name);
            }
        }
        for (Map.Entry<String,Segment> entry : segmentsByName.entrySet()) {
            if (entry.getKey().equals(name)) {
                continue;
            }
            Segment segment2 = entry.getValue();
            // If we were holding another segment in the same job, release it, unlocking its waiter to proceed.
            if (segment2.holding.remove(build) && segment2.waitingContext != null) {
                segment2.waitingContext.onSuccess(null);
                segment2.waitingContext = null;
            }
        }
        if (segment.holding.size() < segment.concurrency) {
            segment.waitingContext = null;
            segment.holding.add(build);
            context.onSuccess(null);
        } else {
            segment.waitingBuild = build;
            segment.waitingContext = context;
        }
        save();
    }

    private static synchronized void exit(Run<?,?> r) {
        load();
        Job<?,?> job = r.getParent();
        String jobName = job.getFullName();
        Map<String,Segment> segmentsByName = segmentsByNameByJob.get(jobName);
        if (segmentsByName == null) {
            return;
        }
        boolean modified = false;
        for (Segment segment : segmentsByName.values()) {
            if (segment.holding.remove(r.number)) {
                modified = true;
                if (segment.waitingContext != null) {
                    segment.waitingContext.onSuccess(null);
                    segment.waitingContext = null;
                }
            }
        }
        if (modified) {
            save();
        }
    }

    private static void cancel(FlowExecution exec, Run<?,?> run) throws IOException, InterruptedException {
        CauseOfInterruption coi = new CauseOfInterruption.UserInterruption("TODO define a new type");
        run.addAction(new InterruptedBuildAction(Collections.singleton(coi)));
        exec.abort();
    }

    private static final class Segment {
        /** number of builds current in this segment */
        final Set<Integer> holding = new TreeSet<Integer>();
        /** maximum permitted size of {@link #holding} */
        int concurrency;
        /** context of the build currently waiting to enter this segment, if any */
        @CheckForNull StepContext waitingContext;
        /** number of the waiting build, if any */
        int waitingBuild;
    }

    @Extension public static final class Listener extends RunListener<Run<?,?>> {
        @Override public void onCompleted(Run<?,?> r, TaskListener listener) {
            exit(r);
        }
    }

    @Extension public static final class DescriptorImpl extends StepDescriptor {

        @Override public Set<Class<?>> getRequiredContext() {
            Set<Class<?>> r = new HashSet<Class<?>>();
            r.add(Run.class);
            r.add(FlowExecution.class);
            r.add(FlowNode.class);
            return r;
        }

        @Override public String getFunctionName() {
            return "segment";
        }

        @Override public Step newInstance(Map<String,Object> arguments) {
            Integer concurrency = (Integer) arguments.get("concurrency");
            return new SegmentStep((String) arguments.get("value"), concurrency != null ? concurrency : Integer.MAX_VALUE);
        }

        @Override public String getDisplayName() {
            return "Segment";
        }

    }

}