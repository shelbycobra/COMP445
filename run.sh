#!/bin/bash

if [ -z "$2" ] 
then
echo "ERROR: No arguments passed."
exit 1
fi
cd $1 && \
javac $2.java && \
java $2 ${@:3}