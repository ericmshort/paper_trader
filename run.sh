#!/bin/bash
export JAVA_HOME=/Users/ericshort/.sdkman/candidates/java/current
export PATH=$JAVA_HOME/bin:/Users/ericshort/maven/mvn/bin:$PATH

~/.sdkman/candidates/maven/current/bin/mvn \
    -f /Users/ericshort/AIProjects/paper_trader_main/pom.xml \
    install -DskipTests -q

exec caffeinate -i ~/.sdkman/candidates/maven/current/bin/mvn \
    -f /Users/ericshort/AIProjects/paper_trader_main/pom.xml \
    -pl trading-ui javafx:run
