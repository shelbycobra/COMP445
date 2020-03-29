# How to run

## Running the HTTP file server


```
./run.sh httpfs [OPTIONS]
```

### Example
```
./run.sh httpfs -vv
```

## Running the HTTP client

```
./run.sh httpc [OPTIONS]
```

### Example
```
./run.sh httpc get -r localhost:3000 localhost:8080 -vv
```

# Testing

## Running concurrent client tests

```
./test.sh concurrent
```

## Running unit tests

```
./test.sh unit
```