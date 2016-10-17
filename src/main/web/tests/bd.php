<?php
  $expand = isset($_REQUEST['expand']);
  $limit  = isset($_REQUEST['limit']) ? intval($_REQUEST['limit']) : 20;

  $filter = array();
  if (isset($_REQUEST['server'])) {
    $filter["server"] = strtoupper($_REQUEST['server']);
  }
  if (isset($_REQUEST['since'])) {
    $filter["date"] = array("$gte" => $_REQUEST['since']);
  }
  if (isset($_REQUEST['id'])) {
    $filter["_id"] = new MongoDB\BSON\ObjectID($_REQUEST['id']);
  }

  $options = array("limit" => $limit, "sort" => array("date" => -1));

  $manager = new MongoDB\Driver\Manager("mongodb://mongo.ncsa.illinois.edu:27017");
  $query = new MongoDB\Driver\Query($filter, $options);
  $cursor = $manager->executeQuery("browndog.test_results", $query);
  $cursor->setTypeMap(['root' => 'array', 'document' => 'array', 'array' => 'array']);

  header('Content-Type: application/json');

  $first = true;
  print("[");
  foreach ($cursor as $document) {
    if ($first) {
      $first = false;
    } else {
      print(",");
    }
    $date = $document["date"];
    echo '{';
    echo '"id": "' . $document["_id"] . '"';
    echo ', "date": ' . $document["date"];#(($date->sec * 1000) + ($date->usec / 1000));
    echo ', "server": "' . $document['server'] . '"';
    echo ', "time": ' . $document['elapsed_time'];
    foreach ($document['tests'] as $k => $v) {
      echo ', "' . $k . '": ' . $v;
    }
    if ($expand) {
      echo ', "results": ' . json_encode($document['results']);
    }
    echo '}';
  }
  echo "]\n";
?>
