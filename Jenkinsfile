node {
  stage 'Checkout'
  checkout scm
  sh 'git reset --hard'
  sh 'git clean -ffdx'

  stage 'Build'
  sh './gradlew assemble deployZip'
  archive 'build/distributions/*.zip'

  stage 'Test'
  sh './gradlew check -PtestIgnoreFailures=true'
  step ([
    $class: 'JUnitResultArchiver',
    testResults: '**/build/test-results/*.xml'
  ])
}
