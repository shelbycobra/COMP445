#!/bin/bash

if [ -z "$2" ] 
then
echo "ERROR: No arguments passed."
exit 1
fi
javac $1.java
java $1 ${@:2}