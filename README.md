# How to run

## Step 1. Run the router
**Example**
```
./router --port 3000 --drop-rate 0.2 --max-delay 10ms --seed 1
```
## Step 2. Run the server and client

### Running the HTTP file server

`./run.sh httpfs [OPTIONS]`

**Example**
```
./run.sh httpfs -vv
```

### Running the HTTP client

`./run.sh httpc [OPTIONS]`

**Example**
```
./run.sh httpc get -r localhost:3000 localhost:8080 -vv
```

# Testing

## Step 1. Run the router at port 3000

**Example**
```
./router --port 3000 --drop-rate 0 --max-delay 0 --seed 1
```

## Step 2. Run the tests

### Running concurrent client tests

```
./test.sh 1         \\ Two clients writing
./test.sh 2         \\ One client reading, one writing
./test.sh 3         \\ Two clients reading
```

### Running unit tests

```
./run.sh unittest
```