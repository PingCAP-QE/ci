class bcolors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'
    UNDERLINE = '\033[4m'
    WHITE = '\033[37m'

bcolor = ['\033[31m', '\033[32m', '\033[96m', '\033[92m', '\033[93m', '\033[91m', '\033[31m', '\033[34m', '\033[35m', '\033[37m']

def PURPLE(str):
     return '\033[95m' + str + '\033[0m'
def BLUE(str):
     return '\033[94m' + str + '\033[0m'
def CYAN(str):
     return '\033[96m' + str + '\033[0m'
def GREEN(str):
     return '\033[92m' + str + '\033[0m'
def YELLOW(str):
     return '\033[93m' + str + '\033[0m'
def RED(str):
     return '\033[91m' + str + '\033[0m'
def BOLD(str):
     return '\033[1m' + str + '\033[0m'
def UNDERLINE(str):
     return '\033[4m' + str + '\033[0m'
def WHITE(str):
     return '\033[37m' + str + '\033[0m'