<?php
  $expand = isset($_REQUEST['expand']);
  $limit  = isset($_REQUEST['limit']) ? $_REQUEST['limit'] : 20;

  $query = array();
  if (isset($_REQUEST['server'])) {
    $query["server"] = strtoupper($_REQUEST['server']);
  }
  if (isset($_REQUEST['since'])) {
    $query["date"] = array("$gte" => $_REQUEST['since']);
  }
  if (isset($_REQUEST['id'])) {
    $query["_id"] = new MongoId($_REQUEST['id']);
  }

  $client = new MongoClient("mongodb://mongo.ncsa.illinois.edu:27017");
  $collection = $client->browndog->test_results;
  $cursor = $collection->find($query);
  $cursor->sort(array('date' => -1));
  $cursor->limit($limit);

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
    echo ', "date": ' . (($date->sec * 1000) + ($date->usec / 1000));
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
