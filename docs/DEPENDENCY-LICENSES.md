# Direct dependency and image license review

Reviewed on 2026-09-01 for the Phase 1 skeleton. This is an engineering inventory, not legal advice.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| Gradle Wrapper | 9.7.0 | Apache-2.0 | https://github.com/gradle/gradle |
| Kotlin JVM and serialization plugins | 2.4.10 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Ktor Server | 3.5.2 | Apache-2.0 | https://github.com/ktorio/ktor |
| kotlinx.serialization | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Flyway OSS core and PostgreSQL module | 13.2.0 | Apache-2.0 | https://github.com/flyway/flyway |
| PostgreSQL JDBC | 42.7.13 | BSD-2-Clause | https://github.com/pgjdbc/pgjdbc |
| HikariCP | 7.0.2 | Apache-2.0 | https://github.com/brettwooldridge/HikariCP |
| SLF4J | 2.0.18 | MIT | https://www.slf4j.org/license.html |
| Logback Classic | 1.6.3 | EPL-1.0 or LGPL-2.1-or-later | https://logback.qos.ch/license.html |
| Testcontainers for Java (test only) | 2.0.5 | MIT | https://github.com/testcontainers/testcontainers-java |
| JUnit Jupiter (test only) | 5.13.4 | EPL-2.0 | https://github.com/junit-team/junit5 |
| PostgreSQL development image | 18.6-alpine | PostgreSQL License plus Alpine packages | https://hub.docker.com/_/postgres |
| Eclipse Temurin build/runtime images | 25.0.4+7 Alpine | GPL-2.0-with-classpath-exception plus Alpine packages | https://hub.docker.com/_/eclipse-temurin |

No source or assets from EVE Static Map Planner, RIFT, or SMT are copied into this repository. The standard Gradle
wrapper bootstrap files were copied from the local Map repository and repointed to the independently downloaded
Gradle 9.7.0 distribution.
