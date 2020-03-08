#!/bin/bash

if [ -z "$1" ] 
then
echo ""
echo "ERROR: Not enough arguments passed."
echo "Usage: ./run.sh javaFileName [args]"
echo ""
exit 1
fi
javac $1.java
java $1 ${@:2}