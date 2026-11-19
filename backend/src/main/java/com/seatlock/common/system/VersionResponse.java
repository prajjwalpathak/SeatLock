package com.seatlock.common.system;

/**
 * Which build is running and which instance answered.
 *
 * @param revision git commit the image was built from ("local" outside Docker/CI)
 * @param instance {@code seatlock.instance-id} of the answering instance (e.g. "app-1" behind the local load balancer)
 */
public record VersionResponse(String revision, String instance) {
}
