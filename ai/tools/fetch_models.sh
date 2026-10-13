#!/bin/sh
# Downloads the open face models used by video KYC and checks their SHA-256.
#   YuNet face detector  - MIT licence        (opencv_zoo/models/face_detection_yunet)
#   SFace face recogniser - Apache 2.0 licence (opencv_zoo/models/face_recognition_sface)
set -e
DIR="${1:-models}"
mkdir -p "$DIR"
BASE=https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models
fetch() {
  [ -f "$DIR/$2" ] && echo "$3  $DIR/$2" | sha256sum -c -s && return 0
  python3 -c "import urllib.request,sys; urllib.request.urlretrieve(sys.argv[1], sys.argv[2])" "$BASE/$1/$2" "$DIR/$2"
  echo "$3  $DIR/$2" | sha256sum -c -
}
fetch face_detection_yunet face_detection_yunet_2023mar.onnx 8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
fetch face_recognition_sface face_recognition_sface_2021dec.onnx 0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79
