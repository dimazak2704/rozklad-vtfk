package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.List;

/** Units of a generation job ({@code generation_job.plan}), needed to read the scheduler's answer. */
public record GenerationPlan(List<PlanUnit> units) {

    public GenerationPlan {
        units = List.copyOf(units);
    }
}
