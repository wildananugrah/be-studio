package com.mhamzah.gateway.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a controller: its request and response bodies are not logged (large or uninteresting, e.g. the API
 * documentation or the Gateway Studio configuration documents). The request line and headers still are.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipBodyLogging {}
