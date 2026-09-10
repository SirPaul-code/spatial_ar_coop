"""Offline regression checks; no native code or network access."""
import unittest
from native_notices import abi_from_path,is_notice
class NoticeRulesTest(unittest.TestCase):
    def test_prefab_and_jni_abis(self):
        for abi in ('arm64-v8a','armeabi-v7a','x86','x86_64'):
            self.assertEqual(abi_from_path('prefab/modules/cv/libs/android.'+abi+'/libopencv_java4.so'),abi)
            self.assertEqual(abi_from_path('jni/'+abi+'/libc++_shared.so'),abi)
        self.assertIsNone(abi_from_path('unknown/lib.so'))
    def test_real_notice_variants(self):
        for name in ('ittnotify-GPL-2.0-only.txt','openexr-AUTHORS.ilmbase','libjpeg-turbo-README.ijg','openexr-AUTHORS.openexr'):
            self.assertTrue(is_notice('sdk/etc/licenses/'+name))
        self.assertTrue(is_notice('sdk/LICENSE'))
        self.assertTrue(is_notice('sdk/NOTICE.txt'))
    def test_no_model_or_font_payload(self):
        for name in ('sdk/etc/haarcascades/haarcascade_license_plate_rus_16stages.xml',
                     'licenses/model.xml','licenses/model.onnx','licenses/font.ttf','license_plate.txt'):
            self.assertFalse(is_notice(name))
if __name__=='__main__': unittest.main()
