#!/bin/bash

if [ -z "$1" ] 
then
echo ""
echo "ERROR: Not enough arguments passed."
echo "Usage: ./run.sh javaFileName [args]"
echo ""
exit 1
fi
javac -sourcepath src src/$1.java -d bin
java -cp bin $1 ${@:2}