#!/bin/bash

if [ -z "$2" ] 
then
echo ""
echo "ERROR: Not enough arguments passed."
echo "Usage: ./run.sh DIR javaFileName [args]"
echo ""
exit 1
fi
cd $1 && \
javac $2.java && \
java $2 ${@:3}