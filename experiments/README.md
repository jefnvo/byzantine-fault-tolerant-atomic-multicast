# Testing ByzCast

## Requirements

1. ansible
2. Python 3
3. Linux or MacOS

## Experiment generator structure


The file **byzcast.config** contains the protocol configurations and should be filling before the execution of any test.
Additionally, the file **hosts.txt** needs to be filling before any tests too, this file contains ip address of each node
that are going to be used in the test.

In **byzcast.config** we have the following parameters:


| Param          | Description                                                                                                   |
|----------------|---------------------------------------------------------------------------------------------------------------|
| nodeQuantity   | Indicates how many nodes will be used in the test                                                             |
| localGroups    | Indicates how many local groups (target groups) will be used in the test.                                     |
| globalGroups   | Indicates how many global groups (auxiliary groups) will be used in the test.                                 |
| faultTolerance | How many fails the group supports. <br/>This parameter is used to define how many nodes will be in each group |
| nodeList       | Inform the path for the file that contains all ip address of each node that will be used.                     |



We have two python scripts:
1. main
2. createGroups

The **main** is responsible to read the **byzcast.config** and to validate the params. The **createGroups** script is called 
by the **main** with all parameters, his task is to create the folders and the files based on parameters passed from main script.

The **experiment** folder has the experiment structure that will be executed.

For local test purpose execute the main script with:
> python3 main.py 
 
the outcome will be inside folder experiment.

## Distribution and remote execution

The distribution and remote execution of the protocol is made with Ansible. The chosen of Ansible was guided by your simplicity because 
it doesn't need any agents or database to run it just need ssh access to all machines to work.

Ansible runs in a Unix like environment, so you need to execute in a Linux or Mac O.S.
To learn how to install Ansible read this [link](https://docs.ansible.com/ansible/latest/installation_guide/intro_installation.html).

Before run ansible playbook you need to do two steps:

1. Fill the file **inventory.yml** with all nodes that you will use on test
2. Set the path of ssh key to access the hosts

After the two steps above execute the playbook with the command:
> ansible-playbook -i inventory.yml playbook.yml

The file *playbook.yml* contains the tasks that will be executed.

Logs of the execution will be in the remote machine on path ~/log.txt

Obs: The ansible will just run the scripts, if the protocol returns some error the ansible will not inform the error, 
so you need to check by yourself the logs of the client execution.