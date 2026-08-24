def jobName = "tikv_ghpr_test"

// you can set maxNumber to reserve not delete job
def maxNumber = 60000

Jenkins.instance.getItemByFullName(jobName).builds.findAll {
  it.number <= maxNumber
}.each {
  it.delete()
}
