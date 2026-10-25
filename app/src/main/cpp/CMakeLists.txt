cmake_minimum_required(VERSION 3.22.1)
project(aslpty C)

add_library(aslpty SHARED pty.c)
target_compile_options(aslpty PRIVATE -O2 -Wall -Wextra)
