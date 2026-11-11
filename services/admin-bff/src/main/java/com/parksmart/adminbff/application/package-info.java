/**
 * Use cases: ADMIN check on every call, then fan out to the owning services through the ports in
 * {@code application.port}. Never depends on infrastructure or api.
 */
package com.parksmart.adminbff.application;
