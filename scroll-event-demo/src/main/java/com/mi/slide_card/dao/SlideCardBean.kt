package com.mi.slide_card.dao

data class SlideCardBean(var postition: Int, var url: String, var name: String) {
    fun setPostition(postition: Int): SlideCardBean {
        this.postition = postition
        return this
    }

    fun setUrl(url: String): SlideCardBean {
        this.url = url
        return this
    }

    fun setName(name: String): SlideCardBean {
        this.name = name
        return this
    }

    companion object {
        fun initDatas(): List<SlideCardBean> {
            val datas: MutableList<SlideCardBean> = ArrayList()
            var i = 1
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic.chinaz.net/files/default/imgs/2024-03-26/0b768140d6f053b0_s.jpg",
                    "美女1"
                )
            )
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic.chinaz.net/files/default/imgs/2024-03-25/339b6502a077957e_s.jpg",
                    "美女2"
                )
            )
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic.chinaz.net/files/default/imgs/2024-04-01/5ecc9ca9589dba0d_s.jpg",
                    "美女3"
                )
            )
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic.chinaz.net/files/default/imgs/2024-03-27/e3e57351347d44a1_s.jpg",
                    "美女4"
                )
            )
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic.chinaz.net/files/default/imgs/2024-03-26/2c588cb4fa44d2af_s.jpg",
                    "美女5"
                )
            )
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic1.chinaz.net/files/default/imgs/2024-01-17/f84ba4c2df77fc04_s.png",
                    "美女6"
                )
            )
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic1.chinaz.net/files/default/imgs/2024-03-15/a68cee79ad43369f_s.jpg",
                    "美女7"
                )
            )
            datas.add(
                SlideCardBean(
                    i++,
                    "https://scpic1.chinaz.net/files/default/imgs/2024-03-15/8d608fa7b57c2e87_s.jpg",
                    "美女8"
                )
            )
            return datas
        }
    }
}
