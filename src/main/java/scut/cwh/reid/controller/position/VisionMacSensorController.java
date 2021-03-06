package scut.cwh.reid.controller.position;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.propertyeditors.CustomDateEditor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import scut.cwh.reid.algo.Reid;
import scut.cwh.reid.domain.*;
import scut.cwh.reid.logic.PositionManager;
import scut.cwh.reid.repository.SensorAreaRepository;
import scut.cwh.reid.repository.VisionMacSensorRepository;
import scut.cwh.reid.repository.WifiSensorRepository;
import scut.cwh.reid.utils.DateUtils;
import scut.cwh.reid.utils.PositionUtils;
import scut.cwh.reid.utils.ResultUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RequestMapping("/sensor")
@CrossOrigin
@Controller
public class VisionMacSensorController {
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setLenient(false);
        binder.registerCustomEditor(Date.class, new CustomDateEditor(dateFormat, true));//true:允许输入空值，false:不能为空值
    }
    @Autowired
    private VisionMacSensorRepository visionMacSensorRepository;

    @Autowired
    private WifiSensorRepository wifiSensorRepository;

    @Autowired
    private SensorAreaRepository sensorAreaRepository;

    @PostMapping(value = "/visionMac")
    public @ResponseBody
    Result recordImg(@RequestBody VisionInfo visionInfo) {
        VisionMacInfo visionMacInfo = new VisionMacInfo();
        visionMacInfo.setBoxes(visionInfo.getBoxes());
        visionMacInfo.setCaptureTime(visionInfo.getCaptureTime());
        visionMacInfo.setFromSensorId(visionInfo.getFromSensorId());
        visionMacInfo.setImgPath(visionInfo.getImgPath());

        Date captureTime = visionMacInfo.getCaptureTime();
        Integer fromSensorId = visionMacInfo.getFromSensorId();
        //查询同时间（3s内）同id传感器上报的macAddress
        List<WifiInfo> wifiInfos = wifiSensorRepository.findALLByCaptureTimeBetweenAndFromSensorId(DateUtils.addSecond(captureTime,-1),DateUtils.addSecond(captureTime,1),fromSensorId);
        //查询附近时间（前360s内）同id传感器上报的行人图片
        List<VisionMacInfo> visionMacInfos = visionMacSensorRepository.findALLByCaptureTimeBetweenAndFromSensorId(DateUtils.addSecond(captureTime,-360),captureTime,fromSensorId);

        if(wifiInfos==null || wifiInfos.size()==0){
            // 情况1：同时间内没有上报的mac地址，遍历查找相似度最大的图片，将其mac地址作为匹配的mac地址
            if(visionMacInfos ==null || visionMacInfos.size()==0){
                visionMacInfo.setMacAddress(null);
            }else{
                //遍历查找相似度最大的图片，将其mac地址作为匹配的mac地址
                String address=null;
                double maxSimilarity=0;
                for(VisionMacInfo previsionMacInfo : visionMacInfos){
                    double curSimilarity = Reid.getSimilarity(visionInfo.getImgPath(),previsionMacInfo.getImgPath());
                    if(curSimilarity>maxSimilarity){
                        maxSimilarity=curSimilarity;
                        address=previsionMacInfo.getMacAddress();
                    }
                }
                visionMacInfo.setMacAddress(address);
            }
        }else if(wifiInfos.size()==1){
            // 情况2：同时间内只有一个上报的mac地址，将其作为匹配的mac地址
            visionMacInfo.setMacAddress(wifiInfos.get(0).getMacAddress());
        }else{
            // 情况3：同时间内有多个上报的mac地址，寻找定位位置在传感器对应区域内，且时间最近的
            //获取该时间（3s内）定位的数据
            List<PositionInfo> positionInfoList = PositionManager.getInstance().queryPositionByWifiInfo(DateUtils.addSecond(captureTime,-1),DateUtils.addSecond(captureTime,1),"-1",wifiInfos);

            //遍历查找相定位区域在传感器对应区域内的mac地址，将其mac地址作为匹配的mac地址
            String address=null;
            for(PositionInfo positionInfo:positionInfoList){
                if(positionInfo.getPositionList()!=null && positionInfo.getPositionList().size()>0){
                    for(Position position:positionInfo.getPositionList()){
                        if(PositionUtils.positionIsInArea(position,sensorAreaRepository.findById(fromSensorId))){
                            address=position.getMacAddress();
                            break;
                        }
                    }
                }
                if(address!=null){
                    break;
                }
            }
            visionMacInfo.setMacAddress(address);
        }

        return ResultUtil.success(visionMacSensorRepository.save(visionMacInfo));
    }
}
