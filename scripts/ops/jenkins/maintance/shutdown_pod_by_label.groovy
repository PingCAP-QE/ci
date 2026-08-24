for (aSlave in hudson.model.Hudson.instance.slaves) {
  println('====================');
  println('Name: ' + aSlave.name);
  if (aSlave.getLabelString() == 'delete_label') {
    println('Shutting down node!!!!');
    aSlave.getComputer().setTemporarilyOffline(true,null);
    aSlave.getComputer().doDoDelete();
  }
}
