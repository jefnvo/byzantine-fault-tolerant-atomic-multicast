#!/bin/bash

server=$(ps aux | grep BatchServerGlobal | grep -v 'grep' | awk '{print $2}')
localServer=$(ps aux | grep Server | grep -v 'grep' | awk '{print $2}')
client=$(ps aux | grep Client | grep -v 'grep' | awk '{print $2}')

if [[ -n $server ]]; then
    echo 'killing server pid=$server'
    kill -9 $server
else
    echo "server is dead"
fi

if [[ -n $localServer ]]; then
    echo 'killing local server pid=$localServer'
    kill -9 $localServer
else
    echo "local server is dead"
fi

if [[ -n $client ]]; then
    echo 'killing client pid=$client'
    kill -9 $client
else
    echo "client is dead"
fi

