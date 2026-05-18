#!/bin/bash
export JAVA_HOME=/Users/ericshort/.sdkman/candidates/java/current
export PATH=$JAVA_HOME/bin:/Users/ericshort/maven/mvn/bin:$PATH

exec caffeinate -i /Users/ericshort/maven/mvn/bin/mvn \
    -f /Users/ericshort/AIProjects/paper_trader/pom.xml \
    -pl trading-ui javafx:run
