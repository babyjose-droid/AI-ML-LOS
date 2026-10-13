package com.rhythm.los.application;

/** Published whenever an application changes state, so the workflow can move to the next stage. */
public record ApplicationChanged(Long applicationId, String actor) {}
