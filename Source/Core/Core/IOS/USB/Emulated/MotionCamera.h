// Copyright 2025 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <vector>

#include "Common/CommonTypes.h"
#include "Common/IOFile.h"
#include "Core/IOS/USB/Common.h"

namespace IOS::HLE::USB
{

enum UVCRequestCodes
{
  SET_CUR = 0x01,
  GET_CUR = 0x81,
  GET_MIN = 0x82,
  GET_MAX = 0x83,
  GET_RES = 0x84,
  GET_LEN = 0x85,
  GET_INF = 0x86,
  GET_DEF = 0x87,
};

enum UVCVideoStreamingControl
{
  VS_CONTROL_UNDEFINED    = 0x00,
  VS_PROBE                = 0x01,
  VS_COMMIT               = 0x02,
  VS_STILL_PROBE          = 0x03,
  VS_STILL_COMMIT         = 0x04,
  VS_STILL_IMAGE_TRIGGER  = 0x05,
  VS_STREAM_ERROR_CODE    = 0x06,
  VS_GENERATE_KEY_FRAME   = 0x07,
  VS_UPDATE_FRAME_SEGMENT = 0x08,
  VS_SYNCH_DELAY          = 0x09,
};

enum UVCTerminalControl
{
  CT_CONTROL_UNDEFINED      = 0x00,
  CT_SCANNING_MODE          = 0x01,
  CT_AE_MODE                = 0x02,
  CT_AE_PRIORITY            = 0x03,
  CT_EXPOSURE_TIME_ABSOLUTE = 0x04,
  CT_EXPOSURE_TIME_RELATIVE = 0x05,
  CT_FOCUS_ABSOLUTE         = 0x06,
  CT_FOCUS_RELATIVE         = 0x07,
  CT_FOCUS_AUTO             = 0x08,
  CT_IRIS_ABSOLUTE          = 0x09,
  CT_IRIS_RELATIVE          = 0x0A,
  CT_ZOOM_ABSOLUTE          = 0x0B,
  CT_ZOOM_RELATIVE          = 0x0C,
  CT_PANTILT_ABSOLUTE       = 0x0D,
  CT_PANTILT_RELATIVE       = 0x0E,
  CT_ROLL_ABSOLUTE          = 0x0F,
  CT_ROLL_RELATIVE          = 0x10,
  CT_PRIVACY                = 0x11,
};

enum UVCProcessingUnitControl
{
  PU_CONTROL_UNDEFINED              = 0x00,
  PU_BACKLIGHT_COMPENSATION         = 0x01,
  PU_BRIGHTNESS                     = 0x02,
  PU_CONTRAST                       = 0x03,
  PU_GAIN                           = 0x04,
  PU_POWER_LINE_FREQUENCY           = 0x05,
  PU_HUE                            = 0x06,
  PU_SATURATION                     = 0x07,
  PU_SHARPNESS                      = 0x08,
  PU_GAMMA                          = 0x09,
  PU_WHITE_BALANCE_TEMPERATURE      = 0x0A,
  PU_WHITE_BALANCE_TEMPERATURE_AUTO = 0x0B,
  PU_WHITE_BALANCE_COMPONENT        = 0x0C,
  PU_WHITE_BALANCE_COMPONENT_AUTO   = 0x0D,
  PU_DIGITAL_MULTIPLIER             = 0x0E,
  PU_DIGITAL_MULTIPLIER_LIMIT       = 0x0F,
  PU_HUE_AUTO                       = 0x10,
  PU_ANALOG_VIDEO_STANDARD          = 0x11,
  PU_ANALOG_LOCK_STATUS             = 0x12,
};

#pragma pack(push, 1)
struct UVCHeader
{
  u8 bHeaderLength;
  union {
    u8 bmHeaderInfo;
    struct {
      u8 frameId : 1;
      u8 endOfFrame : 1;
      u8 presentationTimeStamp : 1;
      u8 sourceClockReference : 1;
      u8 : 1;
      u8 stillImage : 1;
      u8 error : 1;
      u8 endOfHeader : 1;
    };
  };
  // let's skip the optional fiels for now and see how it goes
  /*
  u32 dwPresentationTime;
  union {
    u8 scrSourceClock[6];
    struct {
      u32 sourceTimeClock;
      u16 sofCounter : 11;
      u16 : 5;
    };
  };
  */
};

struct UVCProbeCommitControl
{
  union {
    u16 bmHint;
    struct {
      u16 frameInterval : 1;
      u16 keyFrameRate : 1;
      u16 frameRate : 1;
      u16 compQuality : 1;
      u16 compWindowSize : 1;
      u16 : 11;
    };
  };
  u8 bFormatIndex;
  u8 bFrameIndex;
  u32 dwFrameInterval;
  u16 wKeyFrameRate;
  u16 wPFrameRate;
  u16 wCompQuality;
  u16 wCompWindowSize;
  u16 wDelay;
  u32 dwMaxVideoFrameSize;
  u32 dwMaxPayloadTransferSize;
};

struct UVCImageSize
{
  u16 width;
  u16 height;
};
#pragma pack(pop)

class MotionCamera final : public Device
{
public:
  MotionCamera(EmulationKernel& ios);
  ~MotionCamera() override;
  DeviceDescriptor GetDeviceDescriptor() const override;
  std::vector<ConfigDescriptor> GetConfigurations() const override;
  std::vector<InterfaceDescriptor> GetInterfaces(u8 config) const override;
  std::vector<EndpointDescriptor> GetEndpoints(u8 config, u8 interface, u8 alt) const override;
  bool Attach() override;
  bool AttachAndChangeInterface(u8 interface) override;
  int CancelTransfer(u8 endpoint) override;
  int ChangeInterface(u8 interface) override;
  int GetNumberOfAltSettings(u8 interface) override;
  int SetAltSetting(u8 alt_setting) override;
  int SubmitTransfer(std::unique_ptr<CtrlMessage> message) override;
  int SubmitTransfer(std::unique_ptr<BulkMessage> message) override;
  int SubmitTransfer(std::unique_ptr<IntrMessage> message) override;
  int SubmitTransfer(std::unique_ptr<IsoMessage> message) override;

private:
  void ScheduleTransfer(std::unique_ptr<TransferCommand> command, const std::vector<u8>& data,
                        u64 expected_time_us);

  static DeviceDescriptor s_device_descriptor;
  static std::vector<ConfigDescriptor> s_config_descriptor;
  static std::vector<InterfaceDescriptor> s_interface_descriptor;
  static std::vector<EndpointDescriptor> s_endpoint_descriptor;


  EmulationKernel& m_ios;
  const u16 m_vid = 0x057e;
  const u16 m_pid = 0x030a;
  u8 m_active_interface = 0;
  u8 m_active_altsetting = 0;
  const struct UVCImageSize m_supported_sizes[5] = {{640, 480}, {320, 240}, {160, 120}, {176, 144}, {352, 288}};
  struct UVCImageSize m_active_size;
  u32 m_image_size;
  u32 m_image_pos;
  u8 *m_image_data;
  bool m_frame_id;
};

class CameraData final
{
public:
  CameraData();
  void SetData(const u8* data, u32 length);
  void GetData(const u8* data, u32 length);

private:
  u32 m_image_size;
  u8 *m_image_data;
};
}  // namespace IOS::HLE::USB