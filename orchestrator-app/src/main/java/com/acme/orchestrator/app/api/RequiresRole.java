package com.acme.orchestrator.app.api;

import com.acme.orchestrator.app.api.ApiKeys.Role;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The role an endpoint needs. Without it, any valid API key may read. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
  Role value();
}
