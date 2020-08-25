package bzh.cloud.k8s

import org.junit.jupiter.api.Test

class Leecode {

    /*
     * Given a collection of intervals, merge all overlapping intervals.
     */
    @Test
    fun code56(){

        fun merge(intervals: Array<IntArray>): Array<IntArray> {
            return intervals.foldIndexed(arrayOf(IntArray(2))){index,result,each->

                result
            }
        }

        fun mergeIndex(index:Int,arr:Array<IntArray>){
            
        }


        //[[1,3],[2,6],[8,10],[15,18]]
        val arr = arrayOf(arrayOf(1,3),arrayOf(2,6), arrayOf(6,8), arrayOf(15,18));
    }
}