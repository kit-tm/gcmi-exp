#!/bin/bash
regex="active=([0-9]*)"
echo $1
if [[ $(sudo ovs-ofctl dump-tables $1) =~ $regex ]]
then
    echo "$2, ${BASH_REMATCH[1]}" >> $3
fi