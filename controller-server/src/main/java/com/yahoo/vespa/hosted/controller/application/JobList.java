package com.yahoo.vespa.hosted.controller.application;

import com.google.common.collect.ImmutableList;
import com.yahoo.component.Version;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobType;
import com.yahoo.vespa.hosted.controller.application.JobStatus.JobRun;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTrigger.State;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobError.outOfCapacity;

/**
 * A list of deployment jobs that can be filtered in various ways.
 *
 * @author jvenstad
 */
public class JobList {

    private final ImmutableList<JobStatus> list;
    private final boolean negate;

    private JobList(Iterable<JobStatus> jobs, boolean negate) {
        this.list = ImmutableList.copyOf(jobs);
        this.negate = negate;
    }

    private JobList(Iterable<JobStatus> jobs) {
        this(jobs, false);
    }

    // ----------------------------------- Factories

    public static JobList from(Iterable<JobStatus> jobs) {
        return new JobList(jobs);
    }

    public static JobList from(Application application) {
        return from(application.deploymentJobs().jobStatus().values());
    }

    // ----------------------------------- Accessors

    // TODO: Add sorting based on various stuff, such as deployment order, time of last completion, etc..

    /** Returns the jobstatuses in this as an immutable list */
    public List<JobStatus> asList() { return list; }

    /** Returns the jobstatuses in this as an immutable list after mapping with the given function */
    public <Type> List<Type> mapToList(Function<JobStatus, Type> mapper) {
        return ImmutableList.copyOf(list.stream().map(mapper)::iterator);
    }

    public Optional<State> commonState() { return list.stream().map(State::of).reduce(State::merge); }

    public Optional<ApplicationRevision> commonRevision() { return list.stream().map(State::of).map(State::withoutVersion).reduce(State::merge).flatMap(State::revision); }

    public Optional<Version> commonVersion() { return list.stream().map(State::of).map(State::withoutRevision).reduce(State::merge).flatMap(State::version); }

    public boolean isEmpty() { return list.isEmpty(); }

    public boolean anyMatch() { return ! isEmpty(); }

    public int size() { return list.size(); }

    // ----------------------------------- Basic filters

    /** Negates the next filter operation */
    public JobList not() {
        return new JobList(list, ! negate);
    }

    /** Returns the subset of jobs which are current upgrading */
    public JobList upgrading() { // TODO: Centralise and standardise reasoning about upgrades and revisions.
        return filter(job ->      job.lastSuccess().isPresent()
                             &&   job.lastTriggered().isPresent()
                             && ! job.lastTriggered().get().at().isBefore(job.lastCompleted().get().at())
                             &&   job.lastSuccess().get().version().isBefore(job.lastTriggered().get().version()));
    }

    /** Returns the subset of jobs which are currently running, according to the given timeout */
    public JobList running(Instant timeoutLimit) {
        return filter(job -> job.isRunning(timeoutLimit));
    }

    /** Returns the subset of jobs which are currently failing */
    public JobList failing() {
        return filter(job -> ! job.isSuccess());
    }

    /** Returns the subset of jobs which must be failing due to an application change */
    public JobList failingApplicationChange() {
        return filter(job -> failingApplicationChange(job));
    }

    /** Returns the subset of jobs which are failing with the given job error */
    public JobList failingBecause(DeploymentJobs.JobError error) {
        return filter(job -> job.jobError().filter(error::equals).isPresent());
    }

    /** Returns the subset of jobs of the given type -- most useful when negated */
    public JobList type(JobType type) {
        return filter(job -> job.type() == type);
    }

    /** Returns the subset of jobs of the given type -- most useful when negated */
    public JobList types(Collection<JobType> types) {
        return filter(job -> types.contains(job.type()));
    }

    /** Returns the subset of jobs of which are production jobs */
    public JobList production() {
        return filter(job -> job.type().isProduction());
    }

    // ----------------------------------- JobRun filtering

    /** Returns the list in a state where the next filter is for the lastTriggered run type */
    public JobRunFilter lastTriggered() {
        return new JobRunFilter(job -> job.lastTriggered());
    }

    /** Returns the list in a state where the next filter is for the lastCompleted run type */
    public JobRunFilter lastCompleted() {
        return new JobRunFilter(job -> job.lastCompleted());
    }

    /** Returns the list in a state where the next filter is for the lastSuccess run type */
    public JobRunFilter lastSuccess() {
        return new JobRunFilter(job -> job.lastSuccess());
    }

    /** Returns the list in a state where the next filter is for the firstFailing run type */
    public JobRunFilter firstFailing() {
        return new JobRunFilter(job -> job.firstFailing());
    }


    /** Allows sub-filters for runs of the given kind */
    public class JobRunFilter {

        private final Function<JobStatus, Optional<JobRun>> which;

        private JobRunFilter(Function<JobStatus, Optional<JobRun>> which) {
            this.which = which;
        }

        /** Returns the subset of jobs where the run of the given type exists */
        public JobList present() {
            return filter(run -> true);
        }

        /** Returns the subset of jobs where the run of the given type occurred before the given instant */
        public JobList before(Instant threshold) {
            return filter(run -> run.at().isBefore(threshold));
        }

        /** Returns the subset of jobs where the run of the given type occurred after the given instant */
        public JobList after(Instant threshold) {
            return filter(run -> run.at().isAfter(threshold));
        }

        /** Returns the subset of jobs where the run of the given type was on the given version */
        public JobList on(Version version) {
            return filter(run -> run.version().equals(version));
        }

        public JobList upgrade() {
            return filter(run -> run.upgrade());
        }

        /** Transforms the JobRun condition to a JobStatus condition, by considering only the JobRun mapped by which, and executes */
        private JobList filter(Predicate<JobRun> condition) {
            return JobList.this.filter(job -> which.apply(job).filter(condition).isPresent());
        }

    }


    // ----------------------------------- Internal helpers

    private static boolean failingApplicationChange(JobStatus job) {
        if (   job.isSuccess()) return false;
        if ( ! job.lastSuccess().isPresent()) return true; // An application which never succeeded is surely bad.
        if ( ! job.lastSuccess().get().revision().isPresent()) return true; // Indicates the component job, which is always an application change.
        if ( ! job.firstFailing().get().version().equals(job.lastSuccess().get().version())) return false; // Version change may be to blame.
        return ! job.firstFailing().get().revision().equals(job.lastSuccess().get().revision()); // Return whether there is an application change.
    }

    /** Returns a new JobList which is the result of filtering with the -- possibly negated -- condition */
    private JobList filter(Predicate<JobStatus> condition) {
        return from(list.stream().filter(negate ? condition.negate() : condition)::iterator);
    }

}

