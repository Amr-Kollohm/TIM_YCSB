<!--
Copyright (c) 2015 YCSB contributors. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you
may not use this file except in compliance with the License. You
may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied. See the License for the specific language governing
permissions and limitations under the License. See accompanying
LICENSE file.
-->

# YCSB Benchmark Guide

## Quick Start

### 1. Set Up YCSB

Download the YCSB from this website:

    https://github.com/brianfrankcooper/YCSB/releases/

You can choose to download either the full stable version or just one of the available bindings.

### 2. Build YCSB with Tim Binding

If you downloaded the source version, build YCSB with the Tim binding:

    mvn clean package -pl site.ycsb:tim-binding -am

### 3. Start Your Tim Cache Server

Before running benchmarks, start your Tim cache server:

    # In your Rust project directory
    cargo run

The server should be listening on `http://localhost:3000`

### 4. Run YCSB with Tim Binding

#### Load Phase (Initialize Data)

    ./bin/ycsb load tim -s -P workloads/workloada \
        -p tim.endpoint=http://localhost:3000 \
        -p tim.keyprefix=user \
        -p tim.keylength=8

#### Run Phase (Execute Benchmark)

    ./bin/ycsb run tim -s -P workloads/workloada \
        -p tim.endpoint=http://localhost:3000 \
        -p tim.keyprefix=user \
        -p tim.keylength=8

---

## Binding-Specific Instructions

### Tim Cache Server Binding

#### Configuration Parameters

Set these properties in your workload file or command line:

- `tim.endpoint` (required)
  - URL of your Tim cache server
  - Example: `http://localhost:3000`

- `tim.auth_token`
  - Optional authentication token (Bearer token)
  
- `tim.connectionTimeout`
  - Connection timeout in milliseconds (default: 5000)
  
- `tim.readTimeout`
  - Read timeout in milliseconds (default: 10000)
  
- `tim.keyprefix`
  - Prefix for keys used in scan operations (default: "user")
  
- `tim.keylength`
  - Total length of keys (prefix + numeric part) (default: 8)

#### Example Workload File (`workload_tim.properties`)

```properties
tim.endpoint=http://localhost:3000
tim.keyprefix=user
tim.keylength=8

recordcount=100000
operationcount=5000000
workload=site.ycsb.workloads.CoreWorkload

readallfields=true
requestdistribution=zipfian
readproportion=0.95
updateproportion=0.05