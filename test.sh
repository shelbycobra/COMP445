#!/bin/bash

CMD='java -cp bin/'

if [ "$1" == "1"  ]; then
    echo -e "\nTwo clients are writing to the same file\n"
    echo -e "Running java httpc post -v localhost:8080/test1 -d \"hello1\" -h Overwrite:true"
    echo -e "Running java httpc post -v localhost:8080/test1 -d \"goodbye1\" -h Overwrite:true"
    $CMD httpc post -r localhost:3000 -v localhost:8080/bin/test1 -d "hello1" -h Overwrite:true &
    $CMD httpc post -r localhost:3000 -v localhost:8080/bin/test1 -d "goodbye1" -h Overwrite:true
fi

if [ "$1" == "2" ]; then
    echo -e "\nOne client is reading, while another is writing to the same file\n"
    echo -e "Running java httpc post -v localhost:8080/test2 -d \"goodbye2\" -h Overwrite:true"
    echo -e "Running java httpc get -v localhost:8080/test2"
    echo "hello2" > test2
    $CMD httpc post -r localhost:3000 -v localhost:8080/test2 -d "goodbye2" -h Overwrite:true &
    $CMD httpc get -r localhost:3000 -v localhost:8080/test2
fi

if [ "$1" == "3" ]; then
    echo -e "\nTwo clients are reading the same file.\n"
    echo -e "Running java httpc get -v localhost:8080/test3"
    echo -e "Running java httpc get -v localhost:8080/test3"
    echo "hello3" > test3
    $CMD httpc get -r localhost:3000 -v localhost:8080/test3 &
    $CMD httpc get -r localhost:3000 -v localhost:8080/test3
fi