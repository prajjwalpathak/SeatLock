package com.seatlock.common.system;

/** Which build is running: the git commit the image was built from ("local" outside Docker/CI). */
public record VersionResponse(String revision) {
}
