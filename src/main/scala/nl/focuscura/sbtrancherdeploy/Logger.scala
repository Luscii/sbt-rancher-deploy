package nl.focuscura.sbtrancherdeploy

trait Logger {
  def debug(message: => String)
  def info(message: => String)
  def warn(message: => String)
  def error(message: => String)
}
