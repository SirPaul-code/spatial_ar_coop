#include "stablear/vision_c.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
int main(void){if(stablear_vision_abi_version()!=1)return 1;stablear_vision_tracker*t=stablear_vision_create();if(!t)return 2;const int w=160,h=120;uint8_t*im=(uint8_t*)malloc((size_t)w*h);if(!im)return 3;for(int y=0;y<h;++y)for(int x=0;x<w;++x)im[y*w+x]=(uint8_t)((((x/8)^(y/8))&1)?220:30);(void)stablear_vision_add_root(t,1,im,w,h,80,60);if(!stablear_vision_begin_frame(t,2,im,w,h))return 4;stablear_vision_match m;(void)stablear_vision_track(t,1,1,80,60,&m);stablear_vision_remove(t,1);stablear_vision_clear(t);stablear_vision_destroy(t);free(im);puts("PASS: StableAR vision C ABI lifetime smoke");return 0;}
